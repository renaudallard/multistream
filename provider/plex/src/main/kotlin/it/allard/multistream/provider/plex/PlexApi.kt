package it.allard.multistream.provider.plex

import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.bool
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.int
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
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
            val root = NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()
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

    private suspend fun probe(serverUrl: String, token: String): Boolean = runCatching {
        probeClient.await(Request.Builder().url("${serverUrl.trimEnd('/')}/identity").headers(headers(token)).get().build())
            .use { it.isSuccessful }
    }.getOrDefault(false)

    /** Sign in with a plex.tv account (email + password) and return the account auth token. */
    suspend fun signIn(login: String, password: String): String {
        val body = FormBody.Builder().add("login", login).add("password", password).build()
        client.await(Request.Builder().url(signinUrl).headers(headers(null)).post(body).build()).use { response ->
            val root = NetJson.parseToJsonElement(response.body?.string().orEmpty()).obj()
            if (!response.isSuccessful) {
                val message = root?.get("errors").array()?.firstOrNull()?.obj()?.get("message").string()
                throw PlexApiException(message ?: "Sign-in failed (HTTP ${response.code})", authError = response.code == 401)
            }
            return root?.get("authToken").string()
                ?: throw PlexApiException("No Plex token returned (two-factor enabled? use a server URL + token instead)")
        }
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
