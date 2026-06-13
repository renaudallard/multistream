package it.allard.multistream.provider.plex

import it.allard.multistream.core.net.buildClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers each discovered Plex Media Server's access token by host so poster images can be loaded
 * with the token sent as an X-Plex-Token header instead of baked into the URL. Keeping the token out
 * of the URL stops it from being persisted in the watch database (a tracked title's posterUrl) or
 * written to the image loader's on-disk cache.
 */
object PlexImageAuth {
    private val tokensByHost = ConcurrentHashMap<String, String>()

    /** Record the access token for [serverUrl]'s host (called when a server search runs). */
    fun register(serverUrl: String, token: String) {
        runCatching { serverUrl.toHttpUrl().host }.getOrNull()?.let { tokensByHost[it] = token }
    }

    /** Forget [serverUrl]'s host token (called on logout so images stop authenticating). */
    fun unregister(serverUrl: String) {
        runCatching { serverUrl.toHttpUrl().host }.getOrNull()?.let { tokensByHost.remove(it) }
    }

    /** An OkHttp client that adds X-Plex-Token for requests to a known Plex server host. */
    fun imageClient(): OkHttpClient = buildClient().newBuilder().addInterceptor(authInterceptor).build()

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        val token = tokensByHost[request.url.host]
        val authed = if (token != null && request.header("X-Plex-Token") == null) {
            request.newBuilder().header("X-Plex-Token", token).build()
        } else {
            request
        }
        chain.proceed(authed)
    }
}
