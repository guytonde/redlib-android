package io.github.guytonde.redlib

import android.net.Uri

/** Translates reddit URLs into the equivalent redlib paths. */
object RedditLinks {
    private val urlPattern = Regex("""https?://[^\s<>"]+""")

    fun isReddit(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return isRedditSite(host) || host == "redd.it" || host.endsWith(".redd.it")
    }

    /** Returns the redlib path for a reddit URL, or null when redlib has no equivalent. */
    fun toRedlibPath(uri: Uri): String? {
        if (uri.scheme != "https" && uri.scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.encodedPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.encodedQuery?.let { "?$it" } ?: ""
        return when {
            // reddit.com/media?url=... wraps the real image URL.
            isRedditSite(host) && path == "/media" ->
                uri.getQueryParameter("url")?.let { toRedlibPath(Uri.parse(it)) }
            // redlib has no gallery route, but the post page shows the gallery.
            isRedditSite(host) && path.startsWith("/gallery/") ->
                "/comments/" + path.removePrefix("/gallery/")
            host == "i.redd.it" -> "/img$path"
            host == "preview.redd.it" -> "/preview/pre$path$query"
            host == "external-preview.redd.it" -> "/preview/external-pre$path$query"
            // Short links (redd.it/abc123) map onto redlib's /:id route.
            host == "redd.it" || isRedditSite(host) -> path + query
            else -> null
        }
    }

    /** First reddit link inside shared text such as "look at this https://reddit.com/r/...". */
    fun findIn(text: String): Uri? = urlPattern.findAll(text)
        .map { Uri.parse(it.value.trimEnd('.', ',', ')', '!', '?')) }
        .firstOrNull { isReddit(it) }

    private fun isRedditSite(host: String) = host == "reddit.com" || host.endsWith(".reddit.com")
}
