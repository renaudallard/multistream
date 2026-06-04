package it.allard.multistream.provider.rtbf

import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.obj
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class RtbfApiException(message: String) : Exception(message)

/**
 * RTBF Auvio (Belgian French public TV) search via its BFF API. The endpoint is public — no auth or
 * geo restriction for search — and returns a page of blocks (programs, videos, live, premium). Pure
 * Kotlin + JSON.
 */
class RtbfApi(
    private val client: OkHttpClient = buildClient(),
    private val baseUrl: String = "https://bff-service.rtbf.be/auvio/v1.23",
) {
    suspend fun search(query: String, cookie: String? = null): List<UnifiedSearchResult> {
        val url = "$baseUrl/search?query=${URLEncoder.encode(query, "UTF-8")}"
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
        val request = builder.get().build()
        client.await(request).use { response ->
            if (!response.isSuccessful) throw RtbfApiException("HTTP ${response.code}")
            val root = NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()
                ?: throw RtbfApiException("Expected a JSON object response")
            return RtbfParser.parse(root)
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    }
}
