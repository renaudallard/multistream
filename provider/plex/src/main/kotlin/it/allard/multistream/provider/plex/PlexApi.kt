package it.allard.multistream.provider.plex

import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class PlexApiException(message: String, val authError: Boolean = false) : Exception(message)

/**
 * Plex client. Login exchanges email/password for an X-Plex-Token (plex.tv); search hits the Discover
 * service, which works anonymously but returns the member's watch options when a token is supplied.
 * Pure Kotlin + JSON, MockWebServer-testable.
 */
class PlexApi(
    private val client: OkHttpClient = buildClient(),
    private val signinUrl: String = "https://plex.tv/api/v2/users/signin",
    private val discoverUrl: String = "https://discover.provider.plex.tv/library/search",
) {
    suspend fun login(login: String, password: String): String {
        val body = FormBody.Builder().add("login", login).add("password", password).build()
        val request = Request.Builder().url(signinUrl).headers(plexHeaders()).post(body).build()
        client.await(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401) throw PlexApiException("Invalid Plex credentials", authError = true)
            if (!response.isSuccessful) throw PlexApiException("HTTP ${response.code}: ${text.take(140)}")
            return NetJson.parseToJsonElement(text).obj()?.get("authToken").string()
                ?: throw PlexApiException("No Plex token returned (two-factor enabled?)", authError = true)
        }
    }

    suspend fun search(query: String, token: String?): List<UnifiedSearchResult> {
        val url = "$discoverUrl?query=${URLEncoder.encode(query, "UTF-8")}" +
            "&limit=30&searchTypes=movies,tv&searchProviders=discover&includeMetadata=1"
        val builder = Request.Builder().url(url).headers(plexHeaders())
        token?.let { builder.header("X-Plex-Token", it) }
        client.await(builder.get().build()).use { response ->
            if (response.code == 401) throw PlexApiException("Unauthorized", authError = true)
            if (!response.isSuccessful) throw PlexApiException("HTTP ${response.code}")
            val root = NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj() ?: return emptyList()
            return PlexParser.parse(root)
        }
    }

    private fun plexHeaders(): Headers = Headers.Builder()
        .add("Accept", "application/json")
        .add("X-Plex-Client-Identifier", CLIENT_ID)
        .add("X-Plex-Product", "multistream")
        .add("X-Plex-Version", "1.0")
        .build()

    private companion object {
        const val CLIENT_ID = "it.allard.multistream"
    }
}
