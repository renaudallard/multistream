package it.allard.multistream.provider.disney

import android.util.Log
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.array
import it.allard.multistream.provider.api.runCatchingExceptCancellation
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.bool
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class DisneyApiException(message: String, val authError: Boolean = false) : Exception(message)

data class DisneyTokens(val accessToken: String, val refreshToken: String?)

/**
 * Disney+ bamgrid GraphQL client, modeled on the pydisney wrapper
 * (github.com/pam-param-pam/Disney-Plus-api-wrapper). Auth: client API key from the homepage ->
 * registerDevice -> login -> switchProfile. Search hits the explore service. Pure Kotlin
 * (OkHttp + JSON), so it is unit-testable with MockWebServer.
 */
class DisneyApi(
    private val client: OkHttpClient = buildClient(),
    private val webBase: String = "https://www.disneyplus.com",
    private val apiBase: String = "https://disney.api.edge.bamgrid.com",
) {
    suspend fun login(email: String, password: String): DisneyTokens {
        val clientApiKey = obtainClientApiKey()
        val deviceToken = registerDevice(clientApiKey)
        val (nonProfileToken, profiles) = loginStep(deviceToken, email, password)
        val profileId = pickProfile(profiles) ?: throw DisneyApiException("No usable (unlocked) Disney+ profile")
        return switchProfile(nonProfileToken, profileId)
    }

    suspend fun refresh(refreshToken: String): DisneyTokens {
        val clientApiKey = obtainClientApiKey()
        val body = buildJsonObject {
            put("operationName", "refreshToken")
            put("query", REFRESH_QUERY)
            putJsonObject("variables") { putJsonObject("input") { put("refreshToken", refreshToken) } }
        }
        val root = execPost("$apiBase/graph/v1/device/graphql", body.toString(), authorization = clientApiKey)
        return tokensFrom(root) ?: throw DisneyApiException("Refresh failed", authError = true)
    }

    suspend fun search(query: String, accessToken: String, region: Region): List<UnifiedSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val root = execContentGet("$apiBase/explore/v1.7/search?query=$encoded", accessToken)
        return DisneyParser.parseSearch(root, region)
    }

    /** Entity page -> season list, then one call per season for its episodes. */
    suspend fun getSeasons(entityId: String, accessToken: String): List<Season> {
        val page = execContentGet("$apiBase/explore/v1.9/page/entity-$entityId", accessToken)
        return DisneyParser.parseSeasonRefs(page).map { season ->
            val episodes = runCatchingExceptCancellation {
                DisneyParser.parseEpisodes(
                    execContentGet("$apiBase/explore/v1.7/season/${season.id}", accessToken),
                    season.number,
                )
            }.getOrDefault(emptyList())
            Season(season.number, season.name, episodes)
        }
    }

    /** Synopsis, release year and cast from the same entity page used for seasons. */
    suspend fun getDetails(entityId: String, accessToken: String, ref: ProviderRef): ProviderTitleDetails? {
        val page = execContentGet("$apiBase/explore/v1.9/page/entity-$entityId", accessToken)
        return DisneyParser.parseDetails(page, ref)
    }

    /**
     * Episodes the logged-in member has watched. Reuses the seasons flow (entity page -> season refs ->
     * one season page per season for its items); each season item is expected to carry a per-member
     * progress/bookmark. The RAW season JSON is logged under [WATCH_TAG] so the real progress field can
     * be confirmed from an on-device run, then [DisneyParser.parseWatchedEpisodes] best-effort detects
     * watched episodes from candidate fields.
     */
    suspend fun fetchWatchedEpisodes(entityId: String, accessToken: String): List<EpisodeCoord> {
        val page = execContentGet("$apiBase/explore/v1.9/page/entity-$entityId", accessToken)
        val out = mutableListOf<EpisodeCoord>()
        for (season in DisneyParser.parseSeasonRefs(page)) {
            val seasonPage = runCatchingExceptCancellation {
                execContentGet("$apiBase/explore/v1.7/season/${season.id}", accessToken)
            }.getOrNull() ?: continue
            // Dump the raw season payload (truncated) so the exact watch field is visible on device;
            // the detection in parseWatchedEpisodes is a best-effort guess until confirmed from this.
            Log.i(WATCH_TAG, "entity $entityId season ${season.number} (${season.id}) raw=${seasonPage.toString().take(RAW_LOG_LIMIT)}")
            val watched = DisneyParser.parseWatchedEpisodes(seasonPage, season.number)
            Log.i(WATCH_TAG, "entity $entityId season ${season.number} watched=${watched.size}: $watched | ${DisneyParser.watchDebug(seasonPage, season.number)}")
            out += watched
        }
        return out
    }

    private suspend fun obtainClientApiKey(): String {
        client.await(Request.Builder().url(webBase).header("User-Agent", BROWSER_UA).get().build()).use { response ->
            val html = response.body?.string().orEmpty()
            return CLIENT_API_KEY_REGEX.find(html)?.groupValues?.getOrNull(2)
                ?: throw DisneyApiException("Could not read Disney+ client API key from homepage")
        }
    }

    private suspend fun registerDevice(clientApiKey: String): String {
        val body = buildJsonObject {
            put("operationName", "registerDevice")
            put("query", REGISTER_DEVICE_QUERY)
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("deviceFamily", "N/A")
                    put("applicationRuntime", "N/A")
                    put("deviceProfile", "N/A")
                    put("deviceLanguage", "N/A")
                    put("devicePlatformId", "N/A")
                    putJsonObject("attributes") {
                        listOf(
                            "brand", "browserName", "browserVersion",
                            "manufacturer", "operatingSystem", "operatingSystemVersion",
                        ).forEach { put(it, "N/A") }
                    }
                }
            }
        }
        val root = execPost("$apiBase/graph/v1/device/graphql", body.toString(), bearer = clientApiKey)
        return accessTokenFrom(root) ?: throw DisneyApiException("registerDevice returned no token")
    }

    private suspend fun loginStep(deviceToken: String, email: String, password: String): Pair<String, List<JsonObject>> {
        val body = buildJsonObject {
            put("operationName", "login")
            put("query", LOGIN_QUERY)
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("email", email)
                    put("password", password)
                }
            }
        }
        val root = execPost("$apiBase/v1/public/graphql", body.toString(), bearer = deviceToken)
        val token = accessTokenFrom(root) ?: throw DisneyApiException("Login failed", authError = true)
        val profiles = root["data"].obj()?.get("login").obj()?.get("account").obj()?.get("profiles")
            .array()?.mapNotNull { it as? JsonObject } ?: emptyList()
        return token to profiles
    }

    private suspend fun switchProfile(nonProfileToken: String, profileId: String): DisneyTokens {
        val body = buildJsonObject {
            put("operationName", "switchProfile")
            put("query", SWITCH_PROFILE_QUERY)
            putJsonObject("variables") { putJsonObject("input") { put("profileId", profileId) } }
        }
        val root = execPost("$apiBase/v1/public/graphql", body.toString(), bearer = nonProfileToken)
        return tokensFrom(root) ?: throw DisneyApiException("switchProfile returned no token")
    }

    private fun pickProfile(profiles: List<JsonObject>): String? {
        fun locked(p: JsonObject) =
            p["attributes"].obj()?.get("parentalControls").obj()?.get("isPinProtected").bool() == true
        fun default(p: JsonObject) = p["attributes"].obj()?.get("isDefault").bool() == true
        profiles.firstOrNull { default(it) && !locked(it) }?.let { return it["id"].string() }
        return profiles.firstOrNull { !locked(it) }?.get("id").string()
    }

    private fun accessTokenFrom(root: JsonObject): String? = tokenObject(root)?.get("accessToken").string()

    private fun tokensFrom(root: JsonObject): DisneyTokens? {
        val token = tokenObject(root) ?: return null
        val access = token["accessToken"].string() ?: return null
        return DisneyTokens(access, token["refreshToken"].string())
    }

    private fun tokenObject(root: JsonObject): JsonObject? =
        root["extensions"].obj()?.get("sdk").obj()?.get("token").obj()

    private suspend fun execPost(url: String, body: String, bearer: String? = null, authorization: String? = null): JsonObject {
        val builder = Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        if (authorization != null) builder.header("authorization", authorization)
        return exec(builder.build())
    }

    private suspend fun execContentGet(url: String, accessToken: String): JsonObject {
        val builder = Request.Builder().url(url)
            .header("accept", "application/vnd.media-service+json; version=6")
            .header("User-Agent", BROWSER_UA)
            .header("x-bamsdk-platform", "android")
            .header("x-bamsdk-version", "23.1")
            .header("x-dss-edge-accept", "vnd.dss.edge+json; version=2")
            .header("x-dss-feature-filtering", "true")
            .header("Origin", "https://www.disneyplus.com")
            .header("authorization", accessToken)
            .get()
        return exec(builder.build())
    }

    private suspend fun exec(request: Request): JsonObject {
        client.await(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) throw DisneyApiException("Unauthorized", authError = true)
            if (!response.isSuccessful) throw DisneyApiException("HTTP ${response.code}: ${text.take(160)}")
            val obj = NetJson.parseToJsonElement(text).obj() ?: throw DisneyApiException("Expected a JSON object response")
            // bamgrid returns HTTP 200 with a GraphQL `errors` array on failure; surface the real message.
            obj["errors"].array()?.firstOrNull()?.obj()?.let { error ->
                val message = error["message"].string() ?: "Disney+ request failed"
                val code = error["extensions"].obj()?.get("code").string()
                throw DisneyApiException(if (code != null) "$message ($code)" else message)
            }
            return obj
        }
    }

    private companion object {
        const val WATCH_TAG = "DisneyWatch"
        const val RAW_LOG_LIMIT = 4000
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
        val CLIENT_API_KEY_REGEX =
            Regex("\"clientId\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"clientApiKey\"\\s*:\\s*\"([^\"]+)\"")

        const val REGISTER_DEVICE_QUERY =
            "mutation registerDevice(\$input: RegisterDeviceInput!) { registerDevice(registerDevice: \$input) " +
                "{ grant { grantType assertion }, activeSession { partnerName profile { id } } } }"
        const val LOGIN_QUERY =
            "mutation login(\$input: LoginInput!) { login(login: \$input) { actionGrant account { activeProfile { id } " +
                "profiles { id attributes { isDefault parentalControls { isPinProtected } } } } activeSession { isSubscriber } } }"
        const val SWITCH_PROFILE_QUERY =
            "mutation switchProfile(\$input: SwitchProfileInput!) { switchProfile(switchProfile: \$input) " +
                "{ account { ...account } } } fragment account on Account { id }"
        const val REFRESH_QUERY =
            "mutation refreshToken(\$input:RefreshTokenInput!){refreshToken(refreshToken:\$input){activeSession{sessionId}}}"
    }
}
