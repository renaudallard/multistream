package it.allard.multistream.provider.rts

import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.obj
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class RtsApiException(message: String) : Exception(message)

/**
 * Play RTS (Swiss French public TV) catalog search via the SRG SSR Integration Layer. The endpoint is
 * public and needs no auth; `mediaType=VIDEO` keeps the (very audio-heavy) results to watchable video.
 * Pure Kotlin + JSON.
 */
class RtsApi(
    private val client: OkHttpClient = buildClient(),
    private val baseUrl: String = "https://il.srgssr.ch/integrationlayer/2.0",
    private val businessUnit: String = "rts",
) {
    suspend fun search(query: String): List<UnifiedSearchResult> {
        val url = "$baseUrl/$businessUnit/searchResultMediaList" +
            "?q=${URLEncoder.encode(query, "UTF-8")}&pageSize=30&mediaType=VIDEO"
        return get(url)
    }

    /**
     * Browse the latest videos of a topic (genre). The byTopicUrn endpoint is not business-unit scoped
     * (an `/rts/` prefix returns 404), and the topic urn is the full urn:rts:topic:tv:<id> from
     * `topicList`. The response carries the same media shape as search, under `mediaList`.
     */
    suspend fun browseByTopic(topicUrn: String): List<UnifiedSearchResult> {
        val url = "$baseUrl/mediaList/latest/byTopicUrn/${URLEncoder.encode(topicUrn, "UTF-8")}?pageSize=30"
        return get(url)
    }

    private suspend fun get(url: String): List<UnifiedSearchResult> {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.await(request).use { response ->
            if (!response.isSuccessful) throw RtsApiException("HTTP ${response.code}")
            val root = NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()
                ?: throw RtsApiException("Expected a JSON object response")
            return RtsParser.parse(root)
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    }
}
