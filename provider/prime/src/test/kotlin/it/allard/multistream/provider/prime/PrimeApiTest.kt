package it.allard.multistream.provider.prime

import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.net.buildClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrimeApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PrimeApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = PrimeApi(client = buildClient(), baseUrl = server.url("").toString().removeSuffix("/"))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun search_walksTemplateJsonForTitles() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                "<html><body>" +
                    "<script type=\"text/template\">" +
                    "{\"collections\":{\"items\":[" +
                    "{\"title\":\"The Boys\",\"titleID\":\"0ABC\",\"contentType\":\"SEASON\"}," +
                    "{\"title\":\"The Tomorrow War\",\"gti\":\"amzn1.dv.gti.xyz\",\"contentType\":\"MOVIE\"}" +
                    "]}}" +
                    "</script></body></html>",
            ),
        )
        val results = api.search("the", "at-main=tok; session-id=1", Region("US"))
        assertEquals(2, results.size)
        val boys = results.first { it.title == "The Boys" }
        assertEquals(MediaType.SERIES, boys.type)
        assertEquals("https://app.primevideo.com/detail?gti=0ABC", boys.ref.deepLinkHint)
        assertEquals(MediaType.MOVIE, results.first { it.title == "The Tomorrow War" }.type)
        assertTrue(server.takeRequest().path!!.contains("/gp/video/search?phrase=the"))
    }

    @Test fun forbidden_throwsAuthError() {
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            runBlocking { api.search("x", "bad", Region("US")) }
            throw AssertionError("expected PrimeApiException")
        } catch (e: PrimeApiException) {
            assertTrue(e.authError)
        }
    }
}
