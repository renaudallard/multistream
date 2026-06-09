package it.allard.multistream.provider.rtbf

import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import it.allard.multistream.provider.api.runCatchingExceptCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class RtbfApiException(message: String) : Exception(message)

/**
 * RTBF Auvio (Belgian French public TV) search and category browse via its BFF API. The endpoint is
 * public — no auth or geo restriction — and returns a page of blocks (programs, videos, live,
 * premium). Pure Kotlin + JSON.
 */
class RtbfApi(
    private val client: OkHttpClient = buildClient(),
    private val baseUrl: String = "https://bff-service.rtbf.be/auvio/v1.23",
) {
    suspend fun search(query: String, cookie: String? = null): List<UnifiedSearchResult> {
        val url = "$baseUrl/search?query=${URLEncoder.encode(query, "UTF-8")}"
        return RtbfParser.parse(getJson(url, cookie))
    }

    /**
     * Browse a category (genre). The category page lists widgets, each pointing at its own content
     * URL; the first few program/media list widgets are fetched in parallel and merged, since no
     * single call returns the whole category. `categorySlugId` is the page id, e.g. "documentaires-31".
     */
    suspend fun browseCategory(categorySlugId: String): List<UnifiedSearchResult> {
        val page = getJson("$baseUrl/pages/categorie/$categorySlugId", null)
        val paths = (page["data"].obj()?.get("widgets").array() ?: return emptyList())
            .mapNotNull { it.obj() }
            .filter { it["type"].string() == "PROGRAM_LIST" || it["type"].string() == "MEDIA_LIST" }
            .mapNotNull { it["contentPath"].string() }
            .take(WIDGET_FETCH_LIMIT)
        val out = LinkedHashMap<String, UnifiedSearchResult>()
        coroutineScope {
            paths.map { path ->
                async { runCatchingExceptCancellation { RtbfParser.parseWidget(getJson(path, null)) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }.flatten().forEach { out.putIfAbsent(it.ref.providerTitleId, it) }
        return out.values.toList()
    }

    private suspend fun getJson(url: String, cookie: String?): JsonObject {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
        client.await(builder.get().build()).use { response ->
            if (!response.isSuccessful) throw RtbfApiException("HTTP ${response.code}")
            return NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()
                ?: throw RtbfApiException("Expected a JSON object response")
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
        const val WIDGET_FETCH_LIMIT = 4
    }
}
