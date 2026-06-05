package it.allard.multistream.provider.rtl

import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.int
import it.allard.multistream.core.net.obj
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.UUID

class RtlApiException(message: String) : Exception(message)

/**
 * RTL Play (Belgium) catalog client over DPG Media's lfvp platform (the same backend as VTM GO).
 * Search and detail are anonymous (no auth) but the API is geo-restricted to Belgium. The headers
 * mirror the official app; the brand path segment is `RTL_PLAY`. Pure Kotlin + JSON.
 */
class RtlApi(
    private val client: OkHttpClient = buildClient(),
    private val baseUrl: String = "https://lfvp-api.dpgmedia.net",
) {
    suspend fun search(query: String, region: Region): List<UnifiedSearchResult> {
        val url = "$baseUrl/RTL_PLAY/search?query=${URLEncoder.encode(query, "UTF-8")}"
        client.await(get(url)).use { response ->
            if (!response.isSuccessful) throw RtlApiException("HTTP ${response.code}")
            val root = NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()
                ?: throw RtlApiException("Expected a JSON object response")
            return RtlParser.parse(root, region)
        }
    }

    /** Program synopsis, production year and cast from the detail3 page. */
    suspend fun getDetails(detailId: String, ref: ProviderRef): ProviderTitleDetails? {
        val root = detail3(detailId, null) ?: return null
        return RtlParser.parseDetails(root, ref)
    }

    /** Seasons + episodes: one detail3 call lists the season indices; fetch the rest by index. */
    suspend fun getSeasons(detailId: String): List<Season> {
        val base = detail3(detailId, null) ?: return emptyList()
        val selectedIndex = base["seasonPicker"].obj()?.get("selected").obj()?.get("index").int()
        val indices = RtlParser.seasonIndices(base)
        if (indices.isEmpty()) return RtlParser.parseSeason(base)?.let { listOf(it) }.orEmpty()
        return indices.mapNotNull { index ->
            val root = if (index == selectedIndex) base else detail3(detailId, index)
            root?.let { RtlParser.parseSeason(it) }
        }
    }

    private suspend fun detail3(detailId: String, seasonIndex: Int?): JsonObject? {
        val url = "$baseUrl/RTL_PLAY/detail3/$detailId" +
            if (seasonIndex != null) "?selectedSeasonIndex=$seasonIndex" else ""
        client.await(get(url)).use { response ->
            if (!response.isSuccessful) return null
            return NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()
        }
    }

    private fun get(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/json;image/webp")
        .header("x-app-version", APP_VERSION)
        .header("lfvp-device-segment", "Mobile>Android")
        .header("x-dpg-correlation-id", UUID.randomUUID().toString())
        .header("User-Agent", USER_AGENT)
        .get()
        .build()

    private companion object {
        const val APP_VERSION = "26"
        const val USER_AGENT = "RTL_PLAY/26.260522 (com.tapptic.rtl.tvi; build:1; Android 30)"
    }
}
