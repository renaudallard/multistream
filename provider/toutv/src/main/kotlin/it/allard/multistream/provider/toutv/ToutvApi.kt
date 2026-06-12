package it.allard.multistream.provider.toutv

import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.Season
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
    private val profilingUrl: String = "https://services.radio-canada.ca/ott/profiling",
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

    /** Seasons and episodes of a show, from the (anonymous) detail response. */
    suspend fun getSeasons(slug: String): List<Season> =
        ToutvParser.parseSeasons(getObject("$baseUrl/v2/toutv/show/$slug?device=web"))

    /** Titles for a genre, from the (anonymous) category endpoint. */
    suspend fun browseByGenre(genreSlug: String): List<UnifiedSearchResult> =
        ToutvParser.parseCategory(getObject("$baseUrl/v2/toutv/category/$genreSlug?device=web&pageNumber=1&pageSize=$BROWSE_PAGE_SIZE"))

    /**
     * Episodes the signed-in member has watched. Radio-Canada exposes no full watched history and no
     * inline per-episode flag, only a "continue watching" (myview) resume point per show, so this marks
     * the episodes of the resume season up to that point (and the resume episode itself when finished).
     */
    suspend fun fetchWatchedEpisodes(slug: String, token: String): List<EpisodeCoord> {
        val myview = getAuthedObject("$profilingUrl/v2/toutv/myview?device=web", token) ?: return emptyList()
        val resume = ToutvParser.parseResume(myview, slug) ?: return emptyList()
        val lastWatched = if (resume.completed) resume.episode else resume.episode - 1
        return (1..lastWatched).map { EpisodeCoord(resume.season, it) }
    }

    /**
     * GET a member endpoint with the Bearer token. Throws on a non-2xx (the B2C access token expires
     * after about an hour and is not refreshable, so a 401 must surface as a failed call, not as an
     * empty result the caller can't tell apart from "nothing watched") and on transport errors.
     */
    private suspend fun getAuthedObject(url: String, token: String): JsonObject? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://ici.tou.tv")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.await(request).use { response ->
            if (!response.isSuccessful) throw ToutvApiException("HTTP ${response.code}")
            return NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()
        }
    }

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
        const val BROWSE_PAGE_SIZE = 30
        const val CLIENT_KEY = "90505c8d-9c34-4f34-8da1-3a85bdc6d4f4"
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    }
}
