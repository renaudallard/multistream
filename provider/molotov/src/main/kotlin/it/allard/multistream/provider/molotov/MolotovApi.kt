package it.allard.multistream.provider.molotov

import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class MolotovApiException(message: String, val authError: Boolean = false) : Exception(message)

/** Access + refresh tokens from a Fubo sign-in or refresh. */
data class MolotovTokens(val accessToken: String, val refreshToken: String?)

/** The account and profile ids Fubo wants echoed back on authenticated content calls. */
data class MolotovUser(val userId: String, val profileId: String)

/** The bearer plus identity sent on every authenticated Fubo request. */
data class MolotovAuth(val accessToken: String, val userId: String?, val profileId: String?)

/**
 * Fubo backend client (`api-eu.fubo.tv`), the backend the current Molotov 5.51 app uses. Pure Kotlin
 * (OkHttp + JSON), no Android, so it is unit-testable with MockWebServer. The one load-bearing header
 * is `x-application-id: molotov`, which tells the shared Fubo backend to serve the Molotov tenant;
 * without it the API returns no content. Modeled on the etincelle client
 * (github.com/renaudallard/etincelle), whose contract is validated against the live API.
 */
class MolotovApi(
    private val client: OkHttpClient = buildClient(),
    private val baseUrl: String = "https://api-eu.fubo.tv/",
    // A per-instance id is enough: Fubo derives the market from the egress IP, not this header.
    private val deviceId: String = UUID.randomUUID().toString(),
) {
    suspend fun signin(email: String, password: String): MolotovTokens {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
        }
        val request = request(url("signin"), auth = null).put(body.toString().toRequestBody(JSON_MEDIA)).build()
        val root = execObject(request)
        val access = root["access_token"].string() ?: throw MolotovApiException("Sign-in response missing access token")
        return MolotovTokens(access, root["refresh_token"].string())
    }

    suspend fun fetchUser(accessToken: String): MolotovUser {
        val root = execObject(get(url("user"), MolotovAuth(accessToken, null, null)))
        val data = root["data"].obj() ?: throw MolotovApiException("User response missing data")
        val userId = data["id"].string() ?: throw MolotovApiException("User response missing id")
        val profileId = data["profiles"].array()?.firstOrNull().obj()?.get("id").string()
            ?: throw MolotovApiException("User response missing profile")
        return MolotovUser(userId, profileId)
    }

    suspend fun refresh(refreshToken: String): MolotovTokens {
        // The refresh token rides as the bearer; no other auth headers are sent.
        val request = request(url("refresh"), auth = null)
            .header("authorization", "Bearer $refreshToken")
            .post(ByteArray(0).toRequestBody(JSON_MEDIA))
            .build()
        val root = execObject(request)
        val access = root["access_token"].string() ?: throw MolotovApiException("Refresh failed", authError = true)
        return MolotovTokens(access, root["refresh_token"].string())
    }

    suspend fun search(query: String, auth: MolotovAuth, region: Region): List<UnifiedSearchResult> {
        val root = execElement(get(url("papi/v1/search", "query" to query), auth))
        return MolotovParser.parsePage(root, region)
    }

    /** Seasons and episodes from a series' catch-up ("Regarder maintenant") tab. */
    suspend fun getSeasons(seriesId: String, auth: MolotovAuth): List<Season> {
        val root = execElement(
            get(url("papi/v1/program-details/series/$seriesId", "tabID" to "id-tab-watch-now"), auth),
        )
        return MolotovParser.parseSeasons(root)
    }

    private fun url(path: String, vararg query: Pair<String, String>): HttpUrl {
        val builder = baseUrl.toHttpUrl().newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private fun get(url: HttpUrl, auth: MolotovAuth?): Request = request(url, auth).get().build()

    /** Adds the Fubo client and device headers (and the bearer plus ids when [auth] is set). */
    private fun request(url: HttpUrl, auth: MolotovAuth?): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("user-agent", USER_AGENT)
            .header("x-application-id", APPLICATION_ID)
            .header("x-client-version", CLIENT_VERSION)
            .header("x-os", "android")
            .header("x-os-version", OS_VERSION)
            .header("x-device-app", "android")
            .header("x-device-platform", "android_phone")
            .header("x-device-type", "phone")
            .header("x-device-group", "mobile")
            .header("x-device-brand", "android")
            .header("x-device-model", "android")
            .header("x-device-id", deviceId)
            .header("x-preferred-language", "fr-FR")
            .header("x-supported-streaming-protocols", "hls,dash")
            .header("x-drm-scheme", "widevine")
            .header("x-supported-features", SUPPORTED_FEATURES)
        if (auth != null) {
            builder.header("authorization", "Bearer ${auth.accessToken}")
            auth.userId?.takeIf { it.isNotBlank() }?.let { builder.header("x-user-id", it) }
            auth.profileId?.takeIf { it.isNotBlank() }?.let { builder.header("x-profile-id", it) }
        }
        return builder
    }

    private suspend fun execElement(request: Request): JsonElement {
        client.await(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) throw MolotovApiException("Unauthorized", authError = true)
            if (!response.isSuccessful) throw MolotovApiException("HTTP ${response.code}")
            return NetJson.parseToJsonElement(text)
        }
    }

    private suspend fun execObject(request: Request): JsonObject =
        execElement(request).obj() ?: throw MolotovApiException("Expected a JSON object response")

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val APPLICATION_ID = "molotov"
        const val CLIENT_VERSION = "5.51.0"
        const val OS_VERSION = "16"
        const val USER_AGENT = "MolotovTV/5.51.0 (Linux; U; ANDROID; fr-FR; multistream)"

        // `use_drm_v2_response` and the playback features are what the real app advertises; harmless
        // here (multistream never resolves a stream) but kept so the backend treats us as the app.
        const val SUPPORTED_FEATURES =
            "use_drm_v2_response,playback_template_v2,play_start_from_offset,load_channels_in_guide"
    }
}
