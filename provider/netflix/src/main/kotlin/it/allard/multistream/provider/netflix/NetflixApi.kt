package it.allard.multistream.provider.netflix

import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.model.normalizeTitle
import it.allard.multistream.core.net.InMemoryCookieJar
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.obj
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.cancellation.CancellationException

class NetflixApiException(message: String, val authError: Boolean = false) : Exception(message)

/**
 * Netflix web Shakti/Falcor client. The app's own API is MSL-encrypted, so search uses the website:
 * a logged-in cookie session yields a `reactContext` with the member API base + authURL, then a
 * `pathEvaluator` Falcor request returns the matched videos. Modeled on the Kodi CastagnaIT addon.
 *
 * The session self-heals: cookies live in a jar that captures Netflix's rotations (exposed via
 * [currentCookies] to persist back), and an auth error drops the cached reactContext, re-validates
 * against /browse and retries once before giving up.
 */
class NetflixApi(
    private val homeUrl: String = "https://www.netflix.com/browse",
) {
    private val cookieJar = InMemoryCookieJar()
    private val client = buildClient(cookieJar)

    private data class Session(val memberApi: String, val authUrl: String, val userGuid: String?)

    @Volatile
    private var session: Session? = null

    fun invalidate() {
        session = null
    }

    /** Clear the cached session and cookies — call on a fresh login. */
    fun reset() {
        cookieJar.clear()
        session = null
    }

    /** Current session cookies, including any rotation Netflix applied, for persisting back. */
    fun currentCookies(): String = cookieJar.export(COOKIE_URL)

    suspend fun search(query: String, cookies: String, region: Region): List<UnifiedSearchResult> {
        seed(cookies)
        return withRefresh { doSearch(query, region) }
    }

    /** Seasons + episodes for a show via the clean /metadata endpoint (no Falcor needed). */
    suspend fun getSeasons(videoId: String, cookies: String): List<Season> {
        seed(cookies)
        return withRefresh { doGetSeasons(videoId) }
    }

    /** Synopsis + year from /metadata, plus the cast resolved through one Falcor path. */
    suspend fun getDetails(videoId: String, cookies: String, ref: ProviderRef): ProviderTitleDetails? {
        seed(cookies)
        return withRefresh { doGetDetails(videoId, ref) }
    }

    /** Seed the jar from the stored cookie the first time; later rotations stay in the jar. */
    private fun seed(cookies: String) {
        if (cookieJar.loadForRequest(COOKIE_URL).none { it.name == "NetflixId" }) {
            cookieJar.seed(COOKIE_URL, cookies)
        }
    }

    /** Run [block]; on an auth error, drop the cached session, re-validate against /browse, retry once. */
    private suspend fun <T> withRefresh(block: suspend () -> T): T =
        try {
            block()
        } catch (e: NetflixApiException) {
            if (!e.authError) throw e
            invalidate()
            block()
        }

    private suspend fun doSearch(query: String, region: Region): List<UnifiedSearchResult> {
        val current = session ?: prepareSession().also { session = it }
        val term = query.replace("\\", "").replace("\"", "")
        // Search results are nested Falcor refs: titles[size][range] -> byReference -> videos. Mirror
        // the CastagnaIT addon's two paths (the size level is required) so Netflix materializes the
        // referenced video data instead of returning only the title refs.
        val base = "[\"search\",\"byTerm\",\"|$term\",\"titles\",$PAGE_SIZE"
        val idPath = "$base,[\"id\",\"name\",\"requestId\",\"trackIds\"]]"
        val refPath = "$base,{\"from\":0,\"to\":24},\"reference\",[\"summary\",\"title\"]]"
        val body = "path=$refPath&path=$idPath&authURL=${current.authUrl}"
        val jsonGraph = exec(pathEvaluatorRequest(current, body))["jsonGraph"].obj() ?: return emptyList()
        // Netflix's byTerm search pads the real match with themed suggestions; keep only titles that
        // actually contain the query so the unified results aren't polluted.
        val normQuery = normalizeTitle(query)
        val matched = NetflixParser.parse(jsonGraph, region)
            .filter { normQuery.isBlank() || normalizeTitle(it.title).contains(normQuery) }
        // Boxart isn't materialized through the search reference path, so fetch it for the matched ids
        // in a second pathEvaluator call (this is what the reference client does) and attach posters.
        val posters = orDefault(emptyMap()) { fetchBoxarts(current, matched.map { it.ref.providerTitleId }) }
        return matched.map { result -> posters[result.ref.providerTitleId]?.let { result.copy(posterUrl = it) } ?: result }
    }

    private suspend fun doGetSeasons(videoId: String): List<Season> {
        val current = session ?: prepareSession().also { session = it }
        return NetflixParser.parseSeasons(exec(metadataRequest(current, videoId)))
    }

    private suspend fun doGetDetails(videoId: String, ref: ProviderRef): ProviderTitleDetails? {
        val current = session ?: prepareSession().also { session = it }
        val metadata = exec(metadataRequest(current, videoId))
        val cast = orDefault(emptyList()) { fetchCast(current, videoId) }
        return NetflixParser.parseDetails(metadata, cast, ref)
    }

    /** Run an optional secondary fetch, returning [default] on failure but letting cancellation through. */
    private suspend fun <T> orDefault(default: T, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            default
        }

    /** Resolve poster art for the matched ids: videos[ids].boxarts[size].jpg -> a url. */
    private suspend fun fetchBoxarts(current: Session, ids: List<String>): Map<String, String> {
        val idList = ids.mapNotNull { it.toLongOrNull() }.joinToString(",")
        if (idList.isEmpty()) return emptyMap()
        val sizes = "[\"${NetflixParser.ART_POSTER}\",\"${NetflixParser.ART_LANDSCAPE}\"]"
        val body = "path=[\"videos\",[$idList],\"boxarts\",$sizes,\"jpg\",\"value\"]&authURL=${current.authUrl}"
        val jsonGraph = exec(pathEvaluatorRequest(current, body))["jsonGraph"].obj() ?: return emptyMap()
        return NetflixParser.parseBoxarts(jsonGraph, ids)
    }

    /** One Falcor path resolves a video's billed cast: videos[id].cast -> person[pid].name. */
    private suspend fun fetchCast(current: Session, videoId: String): List<String> {
        val id = videoId.toLongOrNull() ?: return emptyList()
        val path = "[\"videos\",$id,\"cast\",{\"from\":0,\"to\":14},[\"id\",\"name\"]]"
        val body = "path=$path&authURL=${current.authUrl}"
        val jsonGraph = exec(pathEvaluatorRequest(current, body))["jsonGraph"].obj() ?: return emptyList()
        return NetflixParser.parseCast(jsonGraph, videoId)
    }

    private fun metadataRequest(current: Session, videoId: String): Request = Request.Builder()
        .url("${current.memberApi}/metadata?movieid=$videoId&authURL=${current.authUrl}")
        .header("Accept", "application/json")
        .header("User-Agent", USER_AGENT)
        .get()
        .build()

    /** Shared Falcor POST builder for /pathEvaluator (search and cast use identical params/headers). */
    private fun pathEvaluatorRequest(current: Session, body: String): Request {
        val url = "${current.memberApi}/pathEvaluator" +
            "?drmSystem=widevine&falcor_server=0.1.0&withSize=false&materialize=false" +
            "&routeAPIRequestsThroughFTL=false&isVolatileBillboardsEnabled=true&isTop10Supported=true" +
            "&original_path=/shakti/mre/pathEvaluator"
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", USER_AGENT)
            .header("x-netflix.nq.stack", "prod")
            .post(body.toRequestBody(FORM_MEDIA))
        current.userGuid?.let { builder.header("x-netflix.request.client.user.guid", it) }
        return builder.build()
    }

    private suspend fun prepareSession(): Session {
        val request = Request.Builder().url(homeUrl).header("User-Agent", USER_AGENT).get().build()
        client.await(request).use { response ->
            val html = response.body?.string().orEmpty()
            // Pull the few values we need straight out of the reactContext rather than parsing the
            // whole blob: Netflix escapes '/', '<', '>' as \xHH to keep them out of </script>, which
            // is invalid JSON. The member API base is present only on a logged-in (member) page.
            val memberApi = extractMemberApi(html)
                ?: throw NetflixApiException("Netflix session not found (not logged in?)", authError = true)
            val authUrl = extractReactValue(html, "authURL")
                ?: throw NetflixApiException("Netflix authURL missing", authError = true)
            // Profile guid for the x-netflix.request.client.user.guid header (optional, best effort).
            val userGuid = extractReactValue(html, "userGuid") ?: extractReactValue(html, "guid")
            return Session(memberApi, authUrl, userGuid)
        }
    }

    /**
     * The member API base URL. Modern Netflix serves memberapi as an object
     * {protocol, hostname, path[]} (nodequark); older pages used a plain string. Null = not logged in.
     */
    private fun extractMemberApi(html: String): String? {
        extractReactValue(html, "memberapi")?.let { return it }
        val start = html.indexOf("\"memberapi\"")
        if (start < 0) return null
        val region = html.substring(start, minOf(start + 400, html.length))
        val protocol = extractReactValue(region, "protocol") ?: return null
        val hostname = extractReactValue(region, "hostname") ?: return null
        val path = Regex("\"path\"\\s*:\\s*\\[\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(region)?.groupValues?.get(1)?.let(::decodeJsHex) ?: return null
        return "$protocol://$hostname$path"
    }

    /** Read a "key":"value" string out of the page's reactContext, decoding Netflix's \xHH escapes. */
    private fun extractReactValue(html: String, key: String): String? {
        val raw = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(html)?.groupValues?.get(1) ?: return null
        return decodeJsHex(raw)
    }

    /** Decode Netflix's \xHH hex escapes (used in place of '/', '<', '>', '+', '=') to characters. */
    private fun decodeJsHex(s: String): String =
        JS_HEX_ESCAPE.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }

    private suspend fun exec(request: Request): JsonObject {
        client.await(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401) {
                invalidate()
                throw NetflixApiException("Unauthorized", authError = true)
            }
            if (!response.isSuccessful) throw NetflixApiException("HTTP ${response.code}")
            return NetJson.parseToJsonElement(text).obj() ?: throw NetflixApiException("Expected a JSON object response")
        }
    }

    private companion object {
        const val PAGE_SIZE = 47
        val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()
        val COOKIE_URL = "https://www.netflix.com".toHttpUrl()
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
        // Netflix escapes characters as \xHH inside the inline reactContext JSON; decode them.
        val JS_HEX_ESCAPE = Regex("""\\x([0-9A-Fa-f]{2})""")
    }
}
