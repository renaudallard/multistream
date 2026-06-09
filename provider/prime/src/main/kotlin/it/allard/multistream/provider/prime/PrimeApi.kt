package it.allard.multistream.provider.prime

import android.util.Log
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.model.normalizeTitle
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
     * Genre browse. Prime Video has no genre catalog endpoint, but its search is genre-aware: querying a
     * genre keyword returns that genre's titles (localized to the member's catalog). Unlike [search] this
     * keeps every parsed result, since a genre keyword never substring-matches the titles it returns.
     */
    suspend fun browseGenre(keyword: String, cookies: String, region: Region): List<UnifiedSearchResult> {
        val url = "$baseUrl/gp/video/search?phrase=${URLEncoder.encode(keyword, "UTF-8")}"
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

    /**
     * Watch state for a signed-in member, across every season. The logged-in detail page reports each
     * episode's playback progress (1.0 once fully watched) for the selected season only, so the
     * selected season is read from the title page and every other season's page is fetched in parallel
     * via its selector link and unioned, mirroring [getSeasons].
     */
    suspend fun fetchWatchedEpisodes(gti: String, cookies: String): List<EpisodeCoord> {
        val html = fetchDetailHtml("$baseUrl/detail/$gti", cookies)
            ?.takeIf { it.contains("text/template", ignoreCase = true) || it.contains(PrimeParser.HYDRATION_ID) || it.trimStart().startsWith("{") }
            ?: fetchDetailHtml("$baseUrl/gp/video/detail/$gti", cookies)
            ?: run {
                Log.i(WATCH_TAG, "gti=$gti: detail page fetch failed (no body)")
                return emptyList()
            }
        val selected = PrimeParser.parseWatchedEpisodes(html)
        val others = PrimeParser.seasonLinks(html).filter { !it.isSelected }
        val rest = coroutineScope {
            others.map { link ->
                async { fetchDetailHtml("$baseUrl${link.link}", cookies)?.let { PrimeParser.parseWatchedEpisodes(it) }.orEmpty() }
            }.awaitAll()
        }.flatten()
        val watched = (selected + rest).distinct()
        Log.i(WATCH_TAG, "gti=$gti watched=${watched.size}: $watched | fetched=${1 + others.size} | bodyLen=${html.length}")
        return watched
    }

    /**
     * Season/episode enumeration for a series from the logged-in detail page. A modern
     * primevideo.com detail page embeds only the selected season's episodes (public detail path
     * first, then the gp/video fallback); its season selector lists every other season's detail link,
     * so the full run is assembled by fetching each of those pages in parallel and merging the result.
     */
    suspend fun getSeasons(gti: String, cookies: String): List<Season> {
        val html = fetchDetailHtml("$baseUrl/detail/$gti", cookies)
            ?.takeIf { it.contains("text/template", ignoreCase = true) || it.contains(PrimeParser.HYDRATION_ID) || it.trimStart().startsWith("{") }
            ?: fetchDetailHtml("$baseUrl/gp/video/detail/$gti", cookies)
            ?: run {
                Log.i(EPISODES_TAG, "gti=$gti: detail page fetch failed (no body)")
                return emptyList()
            }
        val selected = PrimeParser.parseSeasons(html)
        val have = selected.map { it.seasonNumber }.toSet()
        // The detail page embeds only the selected season; fetch each other season's link in parallel.
        val others = PrimeParser.seasonLinks(html).filter { it.sequenceNumber !in have }
        val rest = coroutineScope {
            others.map { link ->
                async { fetchDetailHtml("$baseUrl${link.link}", cookies)?.let { PrimeParser.parseSeasons(it) }.orEmpty() }
            }.awaitAll()
        }.flatten()
        val seasons = (selected + rest).sortedBy { it.seasonNumber }
        Log.i(
            EPISODES_TAG,
            "gti=$gti seasons=${seasons.size} episodes=${seasons.sumOf { it.episodes.size }} | fetched=${1 + others.size} | bodyLen=${html.length}",
        )
        return seasons
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
        const val EPISODES_TAG = "PrimeEpisodes"
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    }
}
