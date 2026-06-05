package it.allard.multistream.provider.rtl

import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.obj
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.UUID

class RtlApiException(message: String) : Exception(message)

/**
 * RTL Play (Belgium) catalog search via DPG Media's lfvp platform (the same backend as VTM GO).
 * Search is anonymous (no auth) but the API is geo-restricted to Belgium. The headers mirror the
 * official app; the brand path segment is `RTL_PLAY`. Pure Kotlin + JSON, MockWebServer-testable.
 */
class RtlApi(
    private val client: OkHttpClient = buildClient(),
    private val baseUrl: String = "https://lfvp-api.dpgmedia.net",
) {
    suspend fun search(query: String, region: Region): List<UnifiedSearchResult> {
        val url = "$baseUrl/RTL_PLAY/search?query=${URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json;image/webp")
            .header("x-app-version", APP_VERSION)
            .header("lfvp-device-segment", "Mobile>Android")
            .header("x-dpg-correlation-id", UUID.randomUUID().toString())
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.await(request).use { response ->
            if (!response.isSuccessful) throw RtlApiException("HTTP ${response.code}")
            val root = NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()
                ?: throw RtlApiException("Expected a JSON object response")
            return RtlParser.parse(root, region)
        }
    }

    private companion object {
        const val APP_VERSION = "26"
        const val USER_AGENT = "RTL_PLAY/26.260522 (com.tapptic.rtl.tvi; build:1; Android 30)"
    }
}
