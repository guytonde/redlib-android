package io.github.guytonde.redlib

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Runs the redlib binary (shipped as libredlib.so) as a child process that
 * listens on 127.0.0.1 only, and restarts it if it exits unexpectedly.
 *
 * The child lives in the app's process group, so Android kills it together
 * with the app; there is no way for it to outlive us.
 */
object RedlibServer {
    private const val TAG = "redlib"
    private const val BINARY = "libredlib.so"
    private const val MAX_QUICK_FAILURES = 5
    private const val QUICK_FAILURE_WINDOW_MS = 60_000L
    private const val LOG_LINES = 40

    /**
     * Instance defaults. Everything here can still be changed per user on
     * redlib's own settings page.
     */
    private val defaultEnv = mapOf(
        // HLS is the only way reddit videos play with audio; the WebView runs
        // redlib's bundled hls.js for it.
        "REDLIB_DEFAULT_USE_HLS" to "on",
        "REDLIB_DEFAULT_HIDE_HLS_NOTIFICATION" to "on",
        "RUST_LOG" to "warn",
    )

    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private val lock = Any()
    private var supervisor: Thread? = null
    private var process: Process? = null
    private var stopping = false

    private val recentLog = ArrayDeque<String>()

    /** Port redlib is listening on, or 0 while it is not running. */
    @Volatile
    var port: Int = 0
        private set

    /** Why redlib could not be kept running; cleared by [start]. */
    @Volatile
    var failure: String? = null
        private set

    /** Starts redlib unless it is already running. Safe to call repeatedly. */
    fun start(context: Context) {
        synchronized(lock) {
            if (supervisor?.isAlive == true) return
            stopping = false
            failure = null
            val app = context.applicationContext
            supervisor = Thread({ supervise(app) }, "redlib-supervisor").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        val proc: Process?
        synchronized(lock) {
            stopping = true
            supervisor?.interrupt()
            supervisor = null
            proc = process
            process = null
            port = 0
        }
        proc?.let(::kill)
    }

    /** True once redlib accepts connections. Blocks briefly; call off the main thread. */
    fun isAcceptingConnections(timeoutMs: Int = 300): Boolean {
        val p = port
        if (p == 0) return false
        return try {
            Socket().use { it.connect(InetSocketAddress(loopback, p), timeoutMs) }
            true
        } catch (e: IOException) {
            false
        }
    }

    /** The last lines redlib printed, for error screens. */
    fun recentOutput(): String = synchronized(recentLog) { recentLog.joinToString("\n") }

    private fun supervise(context: Context) {
        var quickFailures = 0
        var chosenPort = 0
        while (true) {
            val startedAt = System.nanoTime()
            val proc = try {
                // Keep the same port across restarts so open pages keep working.
                if (chosenPort == 0 || !isPortFree(chosenPort)) chosenPort = freePort()
                launch(context, chosenPort)
            } catch (e: IOException) {
                Log.e(TAG, "could not start redlib", e)
                failure = "Could not start redlib: ${e.message}"
                return
            }
            synchronized(lock) {
                if (stopping) {
                    kill(proc)
                    return
                }
                process = proc
                port = chosenPort
            }
            pumpOutput(proc)

            val exitCode = try {
                proc.waitFor()
            } catch (e: InterruptedException) {
                return
            }
            synchronized(lock) {
                process = null
                port = 0
                if (stopping) return
            }

            val ranMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            Log.w(TAG, "redlib exited with code $exitCode after $ranMs ms")
            quickFailures = if (ranMs > QUICK_FAILURE_WINDOW_MS) 1 else quickFailures + 1
            if (quickFailures >= MAX_QUICK_FAILURES) {
                failure = "redlib keeps exiting (last exit code $exitCode)."
                return
            }
            try {
                Thread.sleep(500L * quickFailures)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun launch(context: Context, port: Int): Process {
        val libDir = context.applicationInfo.nativeLibraryDir
        val binary = File(libDir, BINARY)
        if (!binary.isFile) {
            throw IOException(
                "$BINARY is not in $libDir. Build it with scripts/build-redlib.sh " +
                    "before assembling the APK, and check this device's ABI is in redlib.abis."
            )
        }
        val builder = ProcessBuilder(
            binary.absolutePath,
            "--address", "127.0.0.1",
            "--port", port.toString(),
        )
            .directory(context.filesDir) // redlib reads an optional redlib.toml from here
            .redirectErrorStream(true)
        builder.environment().apply {
            put("LD_LIBRARY_PATH", libDir) // for libc++_shared.so, if it was needed
            put("HOME", context.filesDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            putAll(defaultEnv)
        }
        Log.i(TAG, "starting redlib on 127.0.0.1:$port")
        return builder.start()
    }

    private fun pumpOutput(proc: Process) {
        Thread({
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    Log.i(TAG, line)
                    synchronized(recentLog) {
                        if (recentLog.size >= LOG_LINES) recentLog.pollFirst()
                        recentLog.addLast(line)
                    }
                }
            } catch (e: IOException) {
                // The stream closes when the process dies.
            }
        }, "redlib-log").apply {
            isDaemon = true
            start()
        }
    }

    private fun kill(proc: Process) {
        proc.destroy()
        Thread({
            try {
                if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
            } catch (e: InterruptedException) {
                proc.destroyForcibly()
            }
        }, "redlib-kill").apply {
            isDaemon = true
            start()
        }
    }

    private fun isPortFree(p: Int): Boolean = try {
        ServerSocket(p, 1, loopback).close()
        true
    } catch (e: IOException) {
        false
    }

    private fun freePort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }
}
