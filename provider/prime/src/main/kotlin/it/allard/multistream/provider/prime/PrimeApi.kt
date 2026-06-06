package it.allard.multistream.provider.prime

import android.util.Log
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.model.normalizeTitle
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
            val all = PrimeParser.parse(html, region)
            // Amazon pads matches with related titles; prefer exact title matches but never drop to
            // nothing (a localized title may not substring-match the query).
            val normQuery = normalizeTitle(query)
            return all.filter { normQuery.isBlank() || normalizeTitle(it.title).contains(normQuery) }.ifEmpty { all }
        }
    }

    /**
     * Best-effort watch state for a signed-in member. Prime's web search HTML has no watch fields, so
     * we GET the logged-in title detail page with the stored cookies (the public detail path first,
     * then the gp/video variant as a fallback) and scan its embedded `text/template` JSON.
     *
     * The raw, per-episode watch fields are logged under [WATCH_TAG] so an on-device run reveals
     * whether Prime exposes any resume/progress/watched field at all without full ATV device auth.
     * Without that evidence the parse is a reasonable guess and may legitimately return nothing.
     */
    suspend fun fetchWatchedEpisodes(gti: String, cookies: String): List<EpisodeCoord> {
        val html = fetchDetailHtml("$baseUrl/detail/$gti", cookies)
            ?.takeIf { it.contains("text/template", ignoreCase = true) || it.trimStart().startsWith("{") }
            ?: fetchDetailHtml("$baseUrl/gp/video/detail/$gti", cookies)
            ?: run {
                Log.i(WATCH_TAG, "gti=$gti: detail page fetch failed (no body)")
                return emptyList()
            }
        val watched = PrimeParser.parseWatchedEpisodes(html)
        // Compact, truncated per-episode field summary so the real watch field can be identified on
        // device. The bodyLen tells us whether we got a real logged-in page or a login/redirect stub.
        Log.i(
            WATCH_TAG,
            "gti=$gti watched=${watched.size}: $watched | bodyLen=${html.length} | ${PrimeParser.watchDebug(html)}",
        )
        return watched
    }

    /** GET a detail URL with the member cookies; null on a non-2xx or transport error. */
    private suspend fun fetchDetailHtml(url: String, cookies: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookies)
            .header("Accept", "text/html,application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return runCatching {
            client.await(request).use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }

    private companion object {
        const val WATCH_TAG = "PrimeWatch"
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    }
}
