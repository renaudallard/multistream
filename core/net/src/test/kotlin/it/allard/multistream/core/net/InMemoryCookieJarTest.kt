package it.allard.multistream.core.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryCookieJarTest {

    private val url = "https://example.com/".toHttpUrl()

    @Test fun seedThenExportRoundTrips() {
        val jar = InMemoryCookieJar()
        jar.seed(url, "a=1; b=2")
        val exported = jar.export(url)
        assertTrue(exported, "a=1" in exported)
        assertTrue(exported, "b=2" in exported)
    }

    @Test fun sameNameCookieIsReplaced() {
        val jar = InMemoryCookieJar()
        jar.seed(url, "a=1")
        jar.seed(url, "a=2")
        assertEquals("a=2", jar.export(url))
    }

    @Test fun cookiesAreHostScoped() {
        val jar = InMemoryCookieJar()
        jar.seed(url, "a=1")
        assertEquals("", jar.export("https://other.com/".toHttpUrl()))
    }

    @Test fun clearRemovesEverything() {
        val jar = InMemoryCookieJar()
        jar.seed(url, "a=1")
        jar.clear()
        assertEquals("", jar.export(url))
    }
}
