package it.allard.multistream.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Tolerant JSON reader for volatile, undocumented provider responses. */
val NetJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

fun buildClient(cookieJar: CookieJar = CookieJar.NO_COOKIES): OkHttpClient =
    OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

/** Minimal in-memory cookie jar keyed by host; enough for a single logged-in session. */
class InMemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(url.host) { mutableListOf() }
        synchronized(list) {
            cookies.forEach { cookie ->
                list.removeAll { it.name == cookie.name }
                list.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    fun clear() = store.clear()
}

/** Execute a call off the main thread. */
suspend fun OkHttpClient.await(request: Request): Response =
    withContext(Dispatchers.IO) { newCall(request).execute() }
