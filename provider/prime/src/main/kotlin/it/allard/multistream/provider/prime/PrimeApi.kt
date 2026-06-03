package it.allard.multistream.provider.prime

import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class PrimeApiException(message: String, val authError: Boolean = false) : Exception(message)

/**
 * Prime Video web (primevideo.com) search, cookie-authenticated. Best-effort and the most fragile
 * of the providers: Prime embeds its state as JSON inside `<script type="text/template">` blocks
 * (as the Kodi amazon addon's GrabJSON does), so we extract those and walk them for titles. Needs
 * live verification on a device with an Amazon account.
 */
class PrimeApi(
    private val client: OkHttpClient = buildClient(),
    private val baseUrl: String = "https://www.primevideo.com",
) {
    suspend fun search(query: String, cookies: String, region: Region): List<UnifiedSearchResult> {
        val url = "$baseUrl/gp/video/search?phrase=${URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookies)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.await(request).use { response ->
            val html = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) throw PrimeApiException("Unauthorized", authError = true)
            if (!response.isSuccessful) throw PrimeApiException("HTTP ${response.code}")
            return PrimeParser.parse(html, region)
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    }
}
