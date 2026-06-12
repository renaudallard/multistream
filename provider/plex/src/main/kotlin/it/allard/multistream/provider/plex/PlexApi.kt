package it.allard.multistream.provider.plex

import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.bool
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.int
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import it.allard.multistream.provider.api.runCatchingExceptCancellation
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A plex.tv/link PIN: [code] is shown to the user, [id] is polled for the resulting token. */
data class PlexPin(val id: String, val code: String)

class PlexApiException(message: String, val authError: Boolean = false) : Exception(message)

/**
 * Plex client. Search runs against the public Discover service (works anonymously, personalized with
 * an account token) or a Plex Media Server's own library. A plex.tv sign-in yields the account token,
 * from which the member's own server is auto-discovered via the resources API. Pure Kotlin + JSON.
 */
class PlexApi(
    private val client: OkHttpClient = buildClient(),
    private val signinUrl: String = "https://plex.tv/api/v2/users/signin",
    private val pinsUrl: String = "https://plex.tv/api/v2/pins",
    private val resourcesUrl: String = "https://plex.tv/api/v2/resources",
    private val discoverUrl: String = "https://discover.provider.plex.tv/library/search",
) {
    /** Start the app.plex.tv/auth device login; returns the PIN id and its OAuth code. */
    suspend fun createPin(): PlexPin {
        // strong=true returns the long OAuth code that app.plex.tv/auth consumes from its URL.
        client.await(Request.Builder().url("$pinsUrl?strong=true").headers(headers(null)).post(FormBody.Builder().build()).build()).use { response ->
            val root = parseObject(response.body?.string().orEmpty())
            if (!response.isSuccessful || root == null) throw PlexApiException("Could not start Plex link (HTTP ${response.code})")
            val id = root["id"].int()?.toString() ?: root["id"].string() ?: throw PlexApiException("No pin id")
            val code = root["code"].string() ?: throw PlexApiException("No pin code")
            return PlexPin(id, code)
        }
    }

    /** Poll a PIN until the user links it (returns the account token) or the timeout elapses. */
    suspend fun pollPin(pinId: String, code: String): String? {
        repeat(MAX_POLLS) {
            delay(POLL_INTERVAL_MS)
            val token = client.await(
                Request.Builder().url("$pinsUrl/$pinId?code=${URLEncoder.encode(code, "UTF-8")}").headers(headers(null)).get().build(),
            ).use { response ->
                if (!response.isSuccessful) null
                else NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()?.get("authToken").string()
            }
            if (!token.isNullOrBlank()) return token
        }
        return null
    }
    // Short-timeout client for probing server connections: a server advertises several (local,
    // remote, relay) and some are unreachable from the current network, so they must fail fast.
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(6, TimeUnit.SECONDS).connectTimeout(4, TimeUnit.SECONDS).build()
    }

    /**
     * Discover the member's own Plex Media Server from the account token and return the first
     * reachable (connection URI, server access token). Connections are tried local first and relays
     * last. Null when the account owns no reachable server (search then falls back to Discover).
     */
    suspend fun connectServer(accountToken: String): Pair<String, String>? {
        val resources = client.await(
            Request.Builder().url("$resourcesUrl?includeHttps=1&includeRelay=1").headers(headers(accountToken)).get().build(),
        ).use { response ->
            if (!response.isSuccessful) return null
            NetJson.parseToJsonElement(response.body?.string().orEmpty()) as? JsonArray
        } ?: return null
        for (element in resources) {
            val resource = element.obj() ?: continue
            if ("server" !in (resource["provides"].string() ?: "").split(",")) continue
            val serverToken = resource["accessToken"].string() ?: continue
            // The platform blocks cleartext (targetSdk 35), so a plain-http LAN URI fails the probe and
            // is skipped; an https .plex.direct URI (advertised when the server's secure connections
            // are preferred or required) connects with a valid certificate and is used instead.
            val uris = (resource["connections"].array() ?: continue).mapNotNull { it.obj() }
                .sortedWith(compareBy({ it["relay"].bool() == true }, { it["local"].bool() != true }))
                .mapNotNull { it["uri"].string() }
            for (uri in uris) {
                if (probe(uri, serverToken)) return uri to serverToken
            }
        }
        return null
    }

    private suspend fun probe(serverUrl: String, token: String): Boolean = runCatchingExceptCancellation {
        probeClient.await(Request.Builder().url("${serverUrl.trimEnd('/')}/identity").headers(headers(token)).get().build())
            .use { it.isSuccessful }
    }.getOrDefault(false)

    /** Sign in with a plex.tv account (email + password) and return the account auth token. */
    suspend fun signIn(login: String, password: String): String {
        val body = FormBody.Builder().add("login", login).add("password", password).build()
        client.await(Request.Builder().url(signinUrl).headers(headers(null)).post(body).build()).use { response ->
            val root = parseObject(response.body?.string().orEmpty())
            if (!response.isSuccessful) {
                val message = root?.get("errors").array()?.firstOrNull()?.obj()?.get("message").string()
                throw PlexApiException(message ?: "Sign-in failed (HTTP ${response.code})", authError = response.code == 401)
            }
            return root?.get("authToken").string()
                ?: throw PlexApiException("No Plex token returned (two-factor enabled? use a server URL + token instead)")
        }
    }

    /**
     * Parse a response body as a JSON object, or null when it is not one. Error responses can carry
     * an empty or non-JSON body (a proxy 502 typically has none), which must surface as the HTTP
     * error below, not as a SerializationException from the parse.
     */
    private fun parseObject(text: String): JsonObject? =
        try {
            NetJson.parseToJsonElement(text).obj()
        } catch (e: SerializationException) {
            null
        }

    /** Confirm a Plex Media Server is reachable with the token (validates the optional login). */
    suspend fun verifyServer(serverUrl: String, token: String) {
        val request = Request.Builder().url("${serverUrl.trimEnd('/')}/identity").headers(headers(token)).get().build()
        client.await(request).use { response ->
            if (response.code == 401) throw PlexApiException("Invalid Plex token", authError = true)
            if (!response.isSuccessful) throw PlexApiException("Plex server not reachable (HTTP ${response.code})")
        }
    }

    /** Search a Plex Media Server's own library. */
    suspend fun searchServer(serverUrl: String, token: String, query: String): List<UnifiedSearchResult> {
        val url = "${serverUrl.trimEnd('/')}/hubs/search?query=${URLEncoder.encode(query, "UTF-8")}&limit=30"
        client.await(Request.Builder().url(url).headers(headers(token)).get().build()).use { response ->
            if (response.code == 401) throw PlexApiException("Unauthorized", authError = true)
            if (!response.isSuccessful) throw PlexApiException("HTTP ${response.code}")
            val root = NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj() ?: return emptyList()
            // Make the token available to the image loader by host, then build token-less poster URLs.
            PlexImageAuth.register(serverUrl, token)
            return PlexParser.parse(root, imageBase = serverUrl)
        }
    }

    /** Fetch a server item's full metadata: synopsis, year, and cast (the `Role` tags). */
    suspend fun getDetails(serverUrl: String, token: String, ratingKey: String, ref: ProviderRef): ProviderTitleDetails? {
        val url = "${serverUrl.trimEnd('/')}/library/metadata/$ratingKey"
        client.await(Request.Builder().url(url).headers(headers(token)).get().build()).use { response ->
            if (!response.isSuccessful) return null
            val meta = NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()
                ?.get("MediaContainer").obj()?.get("Metadata").array()?.firstOrNull()?.obj() ?: return null
            return ProviderTitleDetails(
                ref = ref,
                title = meta["title"].string() ?: "",
                type = if (meta["type"].string() == "show") MediaType.SERIES else MediaType.MOVIE,
                year = meta["year"].int(),
                synopsis = meta["summary"].string()?.takeIf { it.isNotBlank() },
                cast = meta["Role"].array()?.mapNotNull { it.obj()?.get("tag").string() }.orEmpty(),
            )
        }
    }

    /**
     * Episodes the member has watched for a show on their own Plex Media Server. `allLeaves` lists
     * every episode of the show (ratingKey) flattened, each carrying its season (`parentIndex`),
     * number (`index`) and `viewCount` (>0 = watched). Server-only: Discover results have no ratingKey
     * on the user's server, so the caller returns empty when no server/token is available.
     */
    suspend fun fetchWatchedEpisodes(serverUrl: String, token: String, ratingKey: String): List<EpisodeCoord> {
        val url = "${serverUrl.trimEnd('/')}/library/metadata/$ratingKey/allLeaves"
        client.await(Request.Builder().url(url).headers(headers(token)).get().build()).use { response ->
            if (response.code == 401) throw PlexApiException("Unauthorized", authError = true)
            if (!response.isSuccessful) throw PlexApiException("HTTP ${response.code}")
            val root = NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj() ?: return emptyList()
            return PlexParser.parseWatchedEpisodes(root)
        }
    }

    /**
     * Seasons and their episodes for a show on the member's own Plex Media Server. Reuses the same
     * `allLeaves` call as the watch fetch (every episode flattened, each with its season `parentIndex`,
     * number `index`, `title`, `summary` and `duration` in ms), grouped into one [Season] per season.
     */
    suspend fun getSeasons(serverUrl: String, token: String, ratingKey: String): List<Season> {
        val url = "${serverUrl.trimEnd('/')}/library/metadata/$ratingKey/allLeaves"
        client.await(Request.Builder().url(url).headers(headers(token)).get().build()).use { response ->
            if (response.code == 401) throw PlexApiException("Unauthorized", authError = true)
            if (!response.isSuccessful) throw PlexApiException("HTTP ${response.code}")
            val root = NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj() ?: return emptyList()
            return PlexParser.parseSeasons(root)
        }
    }

    /** Public Discover search; the optional token adds the member's personalized watch options. */
    suspend fun search(query: String, token: String?): List<UnifiedSearchResult> {
        val url = "$discoverUrl?query=${URLEncoder.encode(query, "UTF-8")}" +
            "&limit=30&searchTypes=movies,tv&searchProviders=discover&includeMetadata=1"
        client.await(Request.Builder().url(url).headers(headers(token)).get().build()).use { response ->
            if (response.code == 401) throw PlexApiException("Unauthorized", authError = true)
            if (!response.isSuccessful) throw PlexApiException("HTTP ${response.code}")
            val root = NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj() ?: return emptyList()
            return PlexParser.parse(root)
        }
    }

    /**
     * Browse a genre on the member's own Plex Media Server. Genre browse lives on the server library
     * (Discover has no such route): each movie/show library section is listed, its genre tags are matched
     * against [genreAliases] by title, and the section is filtered by each matching tag id. A server can
     * carry the same genre under several localized tags (e.g. Comedy and Comédie), so every match is
     * queried and the results merged. Returns empty for a Discover-only account with no server.
     */
    suspend fun browseGenre(genreAliases: List<String>, serverUrl: String?, serverToken: String?): List<UnifiedSearchResult> {
        if (serverUrl == null || serverToken == null) return emptyList()
        val base = serverUrl.trimEnd('/')
        val sections = getJson("$base/library/sections", serverToken)
            ?.get("MediaContainer").obj()?.get("Directory").array()?.mapNotNull { it.obj() }.orEmpty()
            .filter { it["type"].string() == "movie" || it["type"].string() == "show" }
        PlexImageAuth.register(serverUrl, serverToken)
        val out = LinkedHashMap<String, UnifiedSearchResult>()
        for (section in sections) {
            val key = section["key"].string() ?: continue
            val genreIds = getJson("$base/library/sections/$key/genre", serverToken)
                ?.get("MediaContainer").obj()?.get("Directory").array()?.mapNotNull { it.obj() }.orEmpty()
                .filter { g -> genreAliases.any { it.equals(g["title"].string(), ignoreCase = true) } }
                .mapNotNull { it["key"].string() ?: it["id"].int()?.toString() }
            for (genreId in genreIds) {
                getJson("$base/library/sections/$key/all?genre=$genreId&limit=30", serverToken)
                    ?.let { PlexParser.parse(it, imageBase = serverUrl) }
                    ?.forEach { out.putIfAbsent(it.ref.providerTitleId, it) }
            }
        }
        return out.values.toList()
    }

    /** GET a server URL and parse the body to a JSON object; null on any non-2xx or transport error. */
    private suspend fun getJson(url: String, token: String): JsonObject? =
        runCatchingExceptCancellation {
            client.await(Request.Builder().url(url).headers(headers(token)).get().build()).use { response ->
                if (!response.isSuccessful) null
                else NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()
            }
        }.getOrNull()

    private fun headers(token: String?): Headers {
        val builder = Headers.Builder()
            .add("Accept", "application/json")
            .add("X-Plex-Client-Identifier", CLIENT_ID)
            .add("X-Plex-Product", "multistream")
            .add("X-Plex-Version", "1.0")
        token?.let { builder.add("X-Plex-Token", it) }
        return builder.build()
    }

    private companion object {
        const val CLIENT_ID = "it.allard.multistream"
        const val MAX_POLLS = 360
        const val POLL_INTERVAL_MS = 2_000L
    }
}
