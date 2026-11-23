package io.github.guytonde.redlib

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * A WebView pointed at the redlib instance that [RedlibServer] runs on this
 * device. Pages on 127.0.0.1 load in place; every other link opens outside.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var retry: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private var waiter: Thread? = null

    /** Redlib path to open once the server answers, e.g. "/r/rust". */
    private var pendingPath = "/"

    /** Port of the page currently shown, or 0 before the first load. */
    private var loadedPort = 0

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var backCallback: Any? = null // OnBackInvokedCallback, API 33+

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        pendingPath = savedInstanceState?.getString(STATE_PATH) ?: pathFromIntent(intent) ?: "/"
        RedlibServer.start(this)
        waitForServer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val path = pathFromIntent(intent) ?: return
        if (loadedPort != 0 && loadedPort == RedlibServer.port) {
            webView.loadUrl(urlFor(loadedPort, path))
        } else {
            pendingPath = path // picked up when the server is ready
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush() // redlib keeps all settings in cookies
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, currentPath() ?: pendingPath)
    }

    override fun onDestroy() {
        waiter?.interrupt()
        mainHandler.removeCallbacksAndMessages(null)
        root.removeView(webView)
        webView.destroy()
        // Leaving the app stops redlib. Recreation (for example a dark mode
        // switch) keeps it running and the new activity reattaches.
        if (isFinishing) RedlibServer.stop()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Only reached below API 33; newer versions use updateBackHandling().
        if (!handleBack()) super.onBackPressed()
    }

    // ------------------------------------------------------------------ UI

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val background = themeColor(android.R.attr.colorBackground)
        root = FrameLayout(this).apply { setBackgroundColor(background) }

        webView = WebView(this).apply {
            setBackgroundColor(background)
            settings.apply {
                javaScriptEnabled = true // redlib's video player and small helpers
                domStorageEnabled = true
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = true
            }
            webViewClient = Client()
            webChromeClient = ChromeClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)

        progress = ProgressBar(this)
        message = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setTextIsSelectable(true)
        }
        retry = Button(this).apply {
            setText(R.string.retry)
            visibility = View.GONE
            setOnClickListener {
                RedlibServer.start(this@MainActivity)
                waitForServer()
            }
        }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(background)
            isClickable = true // block touches to the page underneath
            addView(progress)
            addView(message)
            addView(retry)
        }

        root.addView(webView, matchParent())
        root.addView(overlay, matchParent())
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 30) {
            // Draw edge to edge (mandatory from API 35) and pad for the bars,
            // cutouts and keyboard ourselves.
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        updateBackHandling()
    }

    private fun showOverlay(text: String, isError: Boolean) {
        overlay.visibility = View.VISIBLE
        progress.visibility = if (isError) View.GONE else View.VISIBLE
        retry.visibility = if (isError) View.VISIBLE else View.GONE
        message.text = text
    }

    // ------------------------------------------------------------------ server

    /** Polls until redlib answers, then opens [pendingPath]. */
    private fun waitForServer() {
        showOverlay(getString(R.string.starting), isError = false)
        waiter?.interrupt()
        waiter = Thread({
            val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
            val self = Thread.currentThread()
            while (!self.isInterrupted) {
                if (RedlibServer.isAcceptingConnections()) {
                    // A newer waiter may have replaced this one while we were connecting.
                    if (!self.isInterrupted) mainHandler.post { onServerReady() }
                    return@Thread
                }
                val failure = RedlibServer.failure
                if (failure != null || System.currentTimeMillis() > deadline) {
                    val details = RedlibServer.recentOutput()
                    val text = (failure ?: getString(R.string.timeout)) +
                        if (details.isNotEmpty()) "\n\n$details" else ""
                    mainHandler.post { if (!isDestroyed) showOverlay(text, isError = true) }
                    return@Thread
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }, "redlib-wait").apply {
            isDaemon = true
            start()
        }
    }

    private fun onServerReady() {
        if (isDestroyed) return
        val port = RedlibServer.port
        if (port == 0) { // exited again in the meantime
            waitForServer()
            return
        }
        message.setText(R.string.loading)
        loadedPort = port
        webView.loadUrl(urlFor(port, pendingPath))
        // The overlay is hidden once the page paints (Client.onPageCommitVisible).
    }

    // ------------------------------------------------------------------ navigation

    private fun pathFromIntent(intent: Intent?): String? {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent?.data
            Intent.ACTION_SEND -> intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { RedditLinks.findIn(it) }
            else -> null
        } ?: return null
        return RedditLinks.toRedlibPath(uri)
    }

    private fun currentPath(): String? =
        webView.url?.let { Uri.parse(it) }?.takeIf { isLocal(it) }?.let { pathOf(it) }

    private fun openExternally(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val intent = if (RedditLinks.isReddit(uri)) {
            // This app may be the reddit link handler; offer everything else.
            Intent.createChooser(view, null).putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(this, MainActivity::class.java)),
            )
        } else {
            view
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ back and fullscreen

    private fun handleBack(): Boolean {
        if (fullscreenView != null) {
            exitFullscreen()
            return true
        }
        if (webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    /**
     * On API 33+ a callback is registered only while there is page history or
     * fullscreen video to leave, so the system back gesture and its animation
     * work normally the rest of the time.
     */
    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        val needed = fullscreenView != null || webView.canGoBack()
        if (needed && backCallback == null) {
            val callback = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (!needed && backCallback != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback as OnBackInvokedCallback)
            backCallback = null
        }
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        fullscreenView = null // set first: the callback below re-enters onHideCustomView
        root.removeView(view)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        setSystemBarsVisible(true)
        updateBackHandling()
    }

    @Suppress("DEPRECATION")
    private fun setSystemBarsVisible(visible: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController ?: return
            if (visible) {
                controller.show(WindowInsets.Type.systemBars())
            } else {
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (visible) {
                View.SYSTEM_UI_FLAG_VISIBLE
            } else {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }
    }

    // ------------------------------------------------------------------ WebView callbacks

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (isLocal(request.url)) return false
            openExternally(request.url)
            return true
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            if (isLocal(Uri.parse(url))) overlay.visibility = View.GONE
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            updateBackHandling()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // Connection level failures on our own origin mean redlib is down
            // or restarting; wait for it and reload the same page.
            val serverDown = error.errorCode == WebViewClient.ERROR_CONNECT ||
                error.errorCode == WebViewClient.ERROR_IO ||
                error.errorCode == WebViewClient.ERROR_TIMEOUT
            if (request.isForMainFrame && isLocal(request.url) && serverDown) {
                pendingPath = pathOf(request.url)
                waitForServer()
            }
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden()
                return
            }
            fullscreenView = view
            fullscreenCallback = callback
            root.addView(view, matchParent())
            setSystemBarsVisible(false)
            updateBackHandling()
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun isLocal(uri: Uri) = uri.host == "127.0.0.1"

    private fun pathOf(uri: Uri) = (uri.encodedPath ?: "/") + (uri.encodedQuery?.let { "?$it" } ?: "")

    private fun urlFor(port: Int, path: String) = "http://127.0.0.1:$port$path"

    private fun themeColor(attr: Int): Int {
        val values = obtainStyledAttributes(intArrayOf(attr))
        try {
            return values.getColor(0, Color.BLACK)
        } finally {
            values.recycle()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private companion object {
        const val STATE_PATH = "path"
        const val STARTUP_TIMEOUT_MS = 60_000L
        const val POLL_INTERVAL_MS = 200L
    }
}
