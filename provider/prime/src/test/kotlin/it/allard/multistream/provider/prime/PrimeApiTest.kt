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

    @Test fun search_walksWholeJsonResponseAndFiltersUnrelated() = runBlocking {
        // Modern Prime returns plain JSON (no text/template); results carry title + titleID + entityType.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"results\":[" +
                    "{\"title\":\"Police Academy\",\"titleID\":\"amzn1.dv.gti.aaa\",\"entityType\":\"Movie\"}," +
                    "{\"title\":\"Police Academy 4 - Citizens on Patrol\",\"titleID\":\"amzn1.dv.gti.bbb\",\"entityType\":\"Movie\"}," +
                    "{\"title\":\"Some Cop Show\",\"titleID\":\"amzn1.dv.gti.ccc\",\"entityType\":\"TV Show\"}" +
                    "]}",
            ),
        )
        val results = api.search("police academy", "at-main=tok", Region("US"))
        // "Some Cop Show" doesn't contain the query, so it is filtered out.
        assertEquals(2, results.size)
        assertEquals(MediaType.MOVIE, results.first { it.title == "Police Academy" }.type)
        assertEquals("https://app.primevideo.com/detail?gti=amzn1.dv.gti.aaa", results.first().ref.deepLinkHint)
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
