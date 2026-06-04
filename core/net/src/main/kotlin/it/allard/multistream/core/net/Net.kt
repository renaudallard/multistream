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
import okhttp3.ResponseBody.Companion.toResponseBody
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

    /** Seed from a request-style "n=v; n=v" cookie header captured elsewhere (e.g. at login). */
    fun seed(url: HttpUrl, cookieHeader: String) {
        val cookies = cookieHeader.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
        if (cookies.isNotEmpty()) saveFromResponse(url, cookies)
    }

    /** The current cookies for [url] as a request-style "n=v; n=v" header. */
    fun export(url: HttpUrl): String =
        loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }

    fun clear() = store.clear()
}

/**
 * Execute a call and fully read its body, all on the IO dispatcher. The body is returned
 * buffered in memory so callers can read it (body.string()) on any thread. execute() alone
 * only reads the headers; a lazy body.string() back on the main thread reads from the socket
 * and throws NetworkOnMainThreadException for any response too large to fit the buffer
 * execute() already filled (e.g. a large gzipped HTML page).
 */
suspend fun OkHttpClient.await(request: Request): Response =
    withContext(Dispatchers.IO) {
        newCall(request).execute().use { response ->
            val type = response.body?.contentType()
            val bytes = response.body?.bytes() ?: ByteArray(0)
            response.newBuilder().body(bytes.toResponseBody(type)).build()
        }
    }
