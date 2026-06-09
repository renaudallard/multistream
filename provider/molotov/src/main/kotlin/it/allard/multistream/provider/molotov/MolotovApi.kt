package it.allard.multistream.provider.molotov

import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.int
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MolotovApiException(message: String, val authError: Boolean = false) : Exception(message)

data class MolotovTokens(val accessToken: String, val refreshToken: String?, val userId: String?)

/**
 * Molotov front API client (`fapi.molotov.tv`), modeled on the working Home Assistant integration
 * at github.com/renaudallard/homeassistant_molotov_tv. Pure Kotlin (OkHttp + JSON), no Android,
 * so it is unit-testable with MockWebServer.
 */
class MolotovApi(
    private val client: OkHttpClient = buildClient(),
    private val baseUrl: String = "https://fapi.molotov.tv/",
    private val language: String = "fr",
) {
    suspend fun login(email: String, password: String): MolotovTokens {
        val body = buildJsonObject {
            put("grant_type", "password")
            put("email", email)
            put("password", password)
        }
        val root = execObject(post("v3.1/auth/login", body.toString(), token = null))
        return tokensFrom(root) ?: throw MolotovApiException("Login response missing access token")
    }

    suspend fun refresh(refreshToken: String): MolotovTokens {
        val root = execObject(get("v3/auth/refresh/$refreshToken", token = null))
        return tokensFrom(root) ?: throw MolotovApiException("Refresh failed", authError = true)
    }

    suspend fun search(query: String, accessToken: String, region: Region): List<UnifiedSearchResult> {
        val body = buildJsonObject { put("query", query) }
        val root = execElement(post("v2/search", body.toString(), token = accessToken))
        return MolotovParser.parse(root, region)
    }

    /**
     * Browse a movie genre. Molotov exposes each genre as a "kind" section of its Films category (id 1);
     * the section has its own paginated endpoint, so one genre is fetched directly without pulling the
     * whole category. The response is the standard section/tile shape the parser already walks.
     */
    suspend fun browseByKind(kindSlug: String, accessToken: String, region: Region): List<UnifiedSearchResult> {
        val root = execElement(get("v2/categories/1/sections/$kindSlug?limit=$BROWSE_LIMIT", token = accessToken))
        return MolotovParser.parse(root, region)
    }

    private fun tokensFrom(root: JsonObject): MolotovTokens? {
        val auth = root["auth"].obj() ?: root
        val access = auth["access_token"].string() ?: return null
        val refresh = auth["refresh_token"].string()
        val account = root["account"].obj()
        val userId = account?.get("id").string() ?: account?.get("id").int()?.toString()
        return MolotovTokens(access, refresh, userId)
    }

    private fun get(path: String, token: String?): Request = baseRequest(path, token).get().build()

    private fun post(path: String, jsonBody: String, token: String?): Request =
        baseRequest(path, token).post(jsonBody.toRequestBody(JSON_MEDIA)).build()

    private fun baseRequest(path: String, token: String?): Request.Builder {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("User-Agent", "Android")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Accept-Language", language)
            .header("orientation", "portrait")
            .header("logged_in", if (token != null) "true" else "false")
            .header("X-Molotov-Agent", MOLOTOV_AGENT)
        if (token != null) builder.header("Authorization", "Bearer $token")
        return builder
    }

    private suspend fun execElement(request: Request): JsonElement {
        client.await(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401) throw MolotovApiException("Unauthorized", authError = true)
            if (!response.isSuccessful) throw MolotovApiException("HTTP ${response.code}")
            return NetJson.parseToJsonElement(text)
        }
    }

    private suspend fun execObject(request: Request): JsonObject =
        execElement(request).obj() ?: throw MolotovApiException("Expected a JSON object response")

    private companion object {
        const val BROWSE_LIMIT = 30
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        // Identity header from the working HA integration (proven against the live API).
        const val MOLOTOV_AGENT =
            "{\"app_name\":\"Molotov\",\"app_version_name\":\"4.27.0\",\"app_id\":\"android_tv_app\"," +
                "\"api_version\":8,\"advertising_id\":null,\"app_build\":8881,\"os\":\"Android\"," +
                "\"os_version\":\"12\",\"os_sdk_version\":31,\"rating\":\"HIGH\",\"type\":\"tv\"," +
                "\"features_supported\":[],\"screen_reader_enabled\":false,\"model\":\"HA\"," +
                "\"device\":\"Molotov HA\",\"brand\":\"Molotov\",\"manufacturer\":\"Molotov\"," +
                "\"display\":\"Molotov HA\",\"serial\":null,\"serial_software\":null," +
                "\"store\":\"google\",\"rooted\":false}"
    }
}
