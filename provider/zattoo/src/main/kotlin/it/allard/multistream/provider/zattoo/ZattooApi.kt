package it.allard.multistream.provider.zattoo

import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.InMemoryCookieJar
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.bool
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import it.allard.multistream.provider.api.runCatchingExceptCancellation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

class ZattooApiException(message: String, val authError: Boolean = false) : Exception(message)

/**
 * Zattoo zapi client, modeled on the maintained Kodi pvr.zattoo addon. The flow is:
 * app token (from the homepage) -> session/hello -> v2/account/login -> session power_guide_hash.
 * Session is cookie-based (held by the client's cookie jar). Pure Kotlin, MockWebServer-testable.
 */
class ZattooApi(
    private val cookieJar: InMemoryCookieJar = InMemoryCookieJar(),
    private val client: OkHttpClient = buildClient(cookieJar),
    baseUrl: String = "https://zattoo.com",
) {
    private val base = baseUrl.removeSuffix("/")
    private val baseHttpUrl = base.toHttpUrl()

    @Volatile
    var powerHash: String? = null
        private set

    @Volatile
    private var loggedIn = false

    fun isLoggedIn(): Boolean = loggedIn && powerHash != null

    /** Drop the local session so the next ensureSession re-logs in after a server-side expiry. */
    fun invalidateSession() {
        loggedIn = false
        powerHash = null
        cookieJar.clear()
    }

    /** The current zapi session cookies, for persisting so a fresh process can resume the session. */
    fun exportSession(): String = cookieJar.export(baseHttpUrl)

    /**
     * Try to resume a previous session from its persisted cookies: seed the jar and ask zapi for the
     * session state. True when the server still considers it logged in (and supplies the power guide
     * hash); false means the cookies are dead and a credential login is needed.
     */
    suspend fun resumeSession(cookieHeader: String): Boolean {
        if (cookieHeader.isBlank()) return false
        cookieJar.seed(baseHttpUrl, cookieHeader)
        val response = runCatchingExceptCancellation { execObject(get("$base/zapi/v2/session")) }.getOrNull()
            ?: return false
        val session = response["session"].obj()
        val hash = session?.get("power_guide_hash").string()
        if (session?.get("loggedin").bool() != true || hash == null) return false
        powerHash = hash
        loggedIn = true
        return true
    }

    suspend fun login(email: String, password: String) {
        val appToken = loadAppToken()
        val hello = execObject(
            postForm(
                "$base/zapi/session/hello",
                mapOf("uuid" to UUID.randomUUID().toString(), "lang" to "en", "format" to "json", "client_app_token" to appToken),
            ),
        )
        if (hello["success"].bool() != true) throw ZattooApiException("Zattoo hello failed")

        val login = execObject(
            postForm(
                "$base/zapi/v2/account/login",
                mapOf("login" to email, "password" to password, "format" to "json", "remember" to "true"),
            ),
        )
        val session = login["session"].obj()
        if (login["success"].bool() != true || session?.get("loggedin").bool() != true) {
            throw ZattooApiException("Zattoo login failed")
        }
        powerHash = session?.get("power_guide_hash").string()
            ?: throw ZattooApiException("Zattoo session missing power_guide_hash")
        loggedIn = true
    }

    /** Search the program guide (live + upcoming) for the given window. */
    suspend fun search(
        query: String,
        region: Region,
        windowHours: Int = 12,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): List<UnifiedSearchResult> {
        val hash = powerHash ?: return emptyList()
        val start = nowSeconds - 3600
        val end = nowSeconds + windowHours.toLong() * 3600
        val url = "$base/zapi/v2/cached/program/power_guide/$hash?start=$start&end=$end&format=json"
        return ZattooParser.parsePowerGuide(execElement(get(url)), query, region)
    }

    private suspend fun loadAppToken(): String {
        // Modern Zattoo serves the app token as JSON at /token.json; fall back to the legacy
        // window.appToken HTML marker (still used by some reseller portals).
        runCatchingExceptCancellation { execObject(get("$base/token.json"))["session_token"].string() }.getOrNull()
            ?.let { return it }
        client.await(get("$base/")).use { response ->
            val html = response.body?.string().orEmpty()
            val marker = "window.appToken = '"
            val start = html.indexOf(marker)
            if (start >= 0) {
                val from = start + marker.length
                val to = html.indexOf('\'', from)
                if (to > from) return html.substring(from, to)
            }
        }
        throw ZattooApiException("Could not read Zattoo app token")
    }

    private fun get(url: String): Request =
        Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()

    private fun postForm(url: String, form: Map<String, String>): Request {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return Request.Builder().url(url).header("User-Agent", USER_AGENT).post(body).build()
    }

    private suspend fun execElement(request: Request): JsonElement {
        client.await(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) throw ZattooApiException("Unauthorized", authError = true)
            if (!response.isSuccessful) throw ZattooApiException("HTTP ${response.code}")
            return NetJson.parseToJsonElement(text)
        }
    }

    private suspend fun execObject(request: Request): JsonObject =
        execElement(request).obj() ?: throw ZattooApiException("Expected a JSON object response")

    private companion object {
        const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 12)"
    }
}
