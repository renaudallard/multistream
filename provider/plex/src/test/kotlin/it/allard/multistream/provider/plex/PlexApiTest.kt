package it.allard.multistream.provider.plex

import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.net.buildClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlexApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PlexApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = PlexApi(
            client = buildClient(),
            signinUrl = server.url("/signin").toString(),
            discoverUrl = server.url("/search").toString(),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun login_returnsToken() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"authToken":"TKN-123"}"""))
        assertEquals("TKN-123", api.login("a@b.c", "pw"))
        assertTrue(server.takeRequest().body.readUtf8().contains("login=a%40b.c"))
    }

    @Test fun search_parsesDiscoverMetadata() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"MediaContainer":{"SearchResults":[{"id":"external","SearchResult":[
                  {"Metadata":{"title":"The Batman","type":"movie","year":2022,"ratingKey":"5d776","slug":"the-batman"}},
                  {"Metadata":{"title":"Batman: The Animated Series","type":"show","ratingKey":"5d9c0","slug":"batman-tas"}}
                ]}]}}
                """.trimIndent(),
            ),
        )
        val results = api.search("batman", "TKN")
        assertEquals(2, results.size)
        val movie = results.first { it.title == "The Batman" }
        assertEquals(MediaType.MOVIE, movie.type)
        assertEquals(2022, movie.year)
        assertEquals("https://watch.plex.tv/movie/the-batman", movie.ref.deepLinkHint)
        assertEquals(MediaType.SERIES, results.first { it.title.startsWith("Batman:") }.type)
        assertTrue(server.takeRequest().path!!.contains("/search?query=batman"))
    }
}
