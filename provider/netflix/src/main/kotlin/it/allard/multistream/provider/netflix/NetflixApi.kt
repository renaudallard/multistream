package it.allard.multistream.provider.netflix

import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class NetflixApiException(message: String, val authError: Boolean = false) : Exception(message)

/**
 * Netflix web Shakti/Falcor client. The app's own API is MSL-encrypted, so search uses the website:
 * a logged-in cookie session yields a `reactContext` with the member API base + authURL, then a
 * `pathEvaluator` Falcor request returns the matched videos. Modeled on the Kodi CastagnaIT addon.
 * Fragile (BUILD_ID rotation, bot defenses) but pure Kotlin and MockWebServer-testable.
 */
class NetflixApi(
    private val client: OkHttpClient = buildClient(),
    private val homeUrl: String = "https://www.netflix.com/browse",
) {
    private data class Session(val pathEvaluatorUrl: String, val authUrl: String)

    @Volatile
    private var session: Session? = null

    fun invalidate() {
        session = null
    }

    suspend fun search(query: String, cookies: String, region: Region): List<UnifiedSearchResult> {
        val current = session ?: prepareSession(cookies).also { session = it }
        val term = query.replace("\\", "").replace("\"", "")
        val path = "[\"search\",\"byTerm\",\"|$term\",\"titles\",{\"from\":0,\"to\":24},[\"summary\",\"title\"]]"
        val body = "path=$path&authURL=${current.authUrl}"
        val url = current.pathEvaluatorUrl +
            "?drmSystem=widevine&falcor_server=0.1.0&withSize=false&materialize=false&original_path=/shakti/mre/pathEvaluator"
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookies)
            .header("Accept", "*/*")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody(FORM_MEDIA))
            .build()
        val jsonGraph = exec(request)["jsonGraph"].obj() ?: return emptyList()
        return NetflixParser.parse(jsonGraph, region)
    }

    private suspend fun prepareSession(cookies: String): Session {
        val request = Request.Builder().url(homeUrl).header("Cookie", cookies).header("User-Agent", USER_AGENT).get().build()
        client.await(request).use { response ->
            val html = response.body?.string().orEmpty()
            val contextJson = REACT_CONTEXT.find(html)?.groupValues?.getOrNull(1)
                ?: throw NetflixApiException("Netflix session not found (not logged in?)", authError = true)
            val models = NetJson.parseToJsonElement(contextJson).obj()?.get("models").obj()
                ?: throw NetflixApiException("Could not parse Netflix reactContext")
            val memberApi = models["services"].obj()?.get("data").obj()?.get("memberapi").string()
                ?: throw NetflixApiException("Netflix member API URL missing")
            val authUrl = models["userInfo"].obj()?.get("data").obj()?.get("authURL").string()
                ?: throw NetflixApiException("Netflix authURL missing", authError = true)
            return Session("$memberApi/pathEvaluator", authUrl)
        }
    }

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
        val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
        val REACT_CONTEXT = Regex("netflix\\.reactContext\\s*=\\s*(\\{.*?});\\s*</script>", RegexOption.DOT_MATCHES_ALL)
    }
}
