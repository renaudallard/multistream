package it.allard.multistream.core.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
        // computeIfAbsent is atomic on a ConcurrentHashMap, so every thread shares the one list and
        // the synchronized block below actually excludes them; Kotlin's getOrPut is not atomic and
        // could hand two threads separate lists, losing one thread's cookies.
        val list = store.computeIfAbsent(url.host) { mutableListOf() }
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
 * Execute a call off the caller's thread and fully read its body, returned buffered in memory so
 * callers can read it (body.string()) on any thread. Uses enqueue + suspendCancellableCoroutine so a
 * cancelled coroutine (e.g. a superseded search) cancels the in-flight call instead of leaving it to
 * run to its timeout. The original socket-backed response is always closed.
 */
suspend fun OkHttpClient.await(request: Request): Response {
    val call = newCall(request)
    return suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // Catch every throwable (a malformed or oversized body can raise more than an
                // IOException, e.g. OutOfMemoryError from bytes()); otherwise it escapes onto OkHttp's
                // dispatcher thread and the continuation is never resumed, hanging the caller forever.
                val buffered = try {
                    response.use {
                        val type = it.body?.contentType()
                        val bytes = it.body?.readCapped() ?: ByteArray(0)
                        it.newBuilder().body(bytes.toResponseBody(type)).build()
                    }
                } catch (e: Throwable) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                    return
                }
                if (!cont.isCancelled) cont.resume(buffered)
            }
        })
    }
}

private const val MAX_BODY_BYTES = 32L * 1024 * 1024

/**
 * Read a response body fully into memory, but fail once it exceeds [MAX_BODY_BYTES] so a hostile or
 * malformed provider can't OOM the process. request() buffers at most the cap plus one byte, so an
 * oversized body is rejected without ever being fully allocated.
 */
private fun ResponseBody.readCapped(): ByteArray {
    val source = source()
    source.request(MAX_BODY_BYTES + 1)
    if (source.buffer.size > MAX_BODY_BYTES) throw IOException("Response body exceeds $MAX_BODY_BYTES bytes")
    return source.readByteArray()
}
