package it.allard.multistream.provider.toutv

import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.obj
import it.allard.multistream.provider.api.runCatchingExceptCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class ToutvApiException(message: String) : Exception(message)

/**
 * ICI Tou.tv (Radio-Canada) catalog client. Search and show-detail are anonymous public endpoints
 * that respond worldwide; only playback is geo-locked to Canada, which this app never resolves. The
 * free-text search param is `term` (the API ignores `query`). Pure Kotlin + JSON, MockWebServer-testable.
 */
class ToutvApi(
    private val client: OkHttpClient = buildClient(),
    private val baseUrl: String = "https://services.radio-canada.ca/ott/catalog",
) {
    suspend fun search(query: String): List<UnifiedSearchResult> {
        val url = "$baseUrl/v2/toutv/search?term=${URLEncoder.encode(query, "UTF-8")}&device=web"
        val results = ToutvParser.parseSearch(getObject(url))
        // A "saison" subtitle reliably marks a series; for the rest the subtitle is ambiguous (a series
        // can show a single-video duration), so confirm those from each show's authoritative detail @type.
        return coroutineScope {
            results.map { result ->
                async {
                    if (result.type == MediaType.SERIES) {
                        result
                    } else {
                        val authoritative = runCatchingExceptCancellation {
                            getDetails(result.ref.providerTitleId, result.ref)?.type
                        }.getOrNull()
                        authoritative?.let { result.copy(type = it) } ?: result
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun getDetails(slug: String, ref: ProviderRef): ProviderTitleDetails? =
        ToutvParser.parseDetails(getObject("$baseUrl/v2/toutv/show/$slug?device=web"), ref)

    private suspend fun getObject(url: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://ici.tou.tv")
            // The public web client key, optional but mimics the official site.
            .header("Authorization", "client-key $CLIENT_KEY")
            .get()
            .build()
        client.await(request).use { response ->
            if (!response.isSuccessful) throw ToutvApiException("HTTP ${response.code}")
            return NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()
                ?: throw ToutvApiException("Expected a JSON object response")
        }
    }

    private companion object {
        const val CLIENT_KEY = "90505c8d-9c34-4f34-8da1-3a85bdc6d4f4"
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    }
}
