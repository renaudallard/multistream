package it.allard.multistream.update

import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.await
import it.allard.multistream.core.net.buildClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** A newer release found on GitHub, with the direct link to its APK asset. */
data class UpdateInfo(val version: String, val apkUrl: String)

/**
 * Checks the project's GitHub releases for a version newer than the running app. Every failure
 * (offline, rate limit, malformed payload) resolves to null so a launch check can never disrupt
 * startup or nag the user. The request goes through the shared [buildClient]/[await] net layer, which
 * caps the response body against an OOM and cancels the call together with the coroutine.
 */
class UpdateChecker(private val currentVersion: String) {

    private val client = buildClient()
    private val mutex = Mutex()
    private val _update = MutableStateFlow<UpdateInfo?>(null)

    /** The newer release once one has been found, or null until then. Observed by the update banner. */
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()

    /**
     * Check GitHub for a newer release unless one was already found. Idempotent and cheap to call
     * repeatedly: it runs on launch and again on each search, and a failed or empty check leaves
     * [update] null so the next call retries; once an update is found it is published and the API is
     * left alone. A config-change recreation re-triggers it but finds the result already cached.
     */
    suspend fun refresh() {
        if (_update.value != null) return
        // tryLock so overlapping triggers (launch + rapid searches) don't pile up redundant calls.
        if (!mutex.tryLock()) return
        try {
            if (_update.value == null) _update.value = fetchLatest()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun fetchLatest(): UpdateInfo? = runCatching {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.await(request).use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            parseUpdate(body, currentVersion)
        }
    }.getOrNull()

    private companion object {
        const val REPO = "renaudallard/multistream"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/" + REPO + "/releases/latest"
        const val USER_AGENT = "multistream-app"
    }
}

/**
 * Turn a GitHub "latest release" payload into an [UpdateInfo] when its tag is newer than
 * [currentVersion] and it ships an APK asset; null otherwise. Pure, so it is unit-tested directly.
 */
internal fun parseUpdate(body: String, currentVersion: String): UpdateInfo? {
    val root = NetJson.parseToJsonElement(body) as? JsonObject ?: return null
    val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
    if (tag.isEmpty() || !isNewer(tag, currentVersion)) return null
    val apkUrl = (root["assets"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true }
        ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
        // Release assets are always served from https://github.com; anything else in the payload
        // (a tampered or proxied response) must not be handed to the browser as the update link.
        ?.takeIf { url -> url.toHttpUrlOrNull()?.let { it.isHttps && it.host == "github.com" } == true }
        ?: return null
    return UpdateInfo(tag.removePrefix("v").removePrefix("V"), apkUrl)
}

/** True when [latest] is a strictly higher dotted version than [current]; a leading "v" is ignored. */
internal fun isNewer(latest: String, current: String): Boolean {
    val l = latest.versionParts()
    val c = current.versionParts()
    for (i in 0 until maxOf(l.size, c.size)) {
        val a = l.getOrElse(i) { 0 }
        val b = c.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

private fun String.versionParts(): List<Int> =
    trim().removePrefix("v").removePrefix("V")
        .split(".")
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
