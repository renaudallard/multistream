package it.allard.multistream.provider.arte

import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.obj
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class ArteApiException(message: String) : Exception(message)

/**
 * Arte public catalog search via the EMAC v4 API (the arte.tv website backend). No authentication is
 * needed — arte.tv is free. Results come from the SEARCH page's "listing" zone. Pure Kotlin + JSON.
 */
class ArteApi(
    private val client: OkHttpClient = buildClient(),
    private val baseUrl: String = "https://api.arte.tv/api/emac/v4",
) {
    suspend fun search(query: String, lang: String): List<UnifiedSearchResult> {
        val url = "$baseUrl/$lang/web/pages/SEARCH/?query=${URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.await(request).use { response ->
            if (!response.isSuccessful) throw ArteApiException("HTTP ${response.code}")
            val root = NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()
                ?: throw ArteApiException("Expected a JSON object response")
            return ArteParser.parse(root, lang)
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    }
}
