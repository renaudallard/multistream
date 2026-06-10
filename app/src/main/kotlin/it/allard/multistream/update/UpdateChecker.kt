package it.allard.multistream.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** A newer release found on GitHub, with the direct link to its APK asset. */
data class UpdateInfo(val version: String, val apkUrl: String)

/**
 * Checks the project's GitHub releases for a version newer than the running app. Every failure
 * (offline, rate limit, malformed payload) resolves to null so a launch check can never disrupt
 * startup or nag the user.
 */
class UpdateChecker(private val currentVersion: String) {

    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)
                val tag = json.optString("tag_name")
                if (tag.isEmpty() || !isNewer(tag, currentVersion)) return@use null
                val apkUrl = json.optJSONArray("assets").firstApkUrl() ?: return@use null
                UpdateInfo(tag.removePrefix("v").removePrefix("V"), apkUrl)
            }
        }.getOrNull()
    }

    private companion object {
        const val REPO = "renaudallard/multistream"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/" + REPO + "/releases/latest"
        const val USER_AGENT = "multistream-app"
    }
}

/** Picks the first asset whose name looks like an APK and returns its direct download URL. */
private fun JSONArray?.firstApkUrl(): String? {
    if (this == null) return null
    for (i in 0 until length()) {
        val asset = optJSONObject(i) ?: continue
        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
            return asset.optString("browser_download_url").ifEmpty { null }
        }
    }
    return null
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
