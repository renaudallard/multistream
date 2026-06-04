package it.allard.multistream.provider.rts

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

class RtsApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RtsApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = RtsApi(client = buildClient(), baseUrl = server.url("").toString().removeSuffix("/"))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun search_keepsVideosAndBuildsDeepLink() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"total":2,"searchResultMediaList":[
                  {"urn":"urn:rts:video:15283680","mediaType":"VIDEO","title":"Le 19h30","imageUrl":"https://img.rts.ch/x.jpg"},
                  {"urn":"urn:rts:audio:abc","mediaType":"AUDIO","title":"Une radio","imageUrl":"https://img.rts.ch/a.jpg"}
                ]}
                """.trimIndent(),
            ),
        )
        val results = api.search("19h30")
        assertEquals(1, results.size)
        val video = results.first()
        assertEquals("Le 19h30", video.title)
        assertEquals(MediaType.MOVIE, video.type)
        assertEquals("https://www.rts.ch/play/tv/redirect/detail/15283680", video.ref.deepLinkHint)
        assertEquals(
            "https://il.srgssr.ch/images/?imageUrl=https%3A%2F%2Fimg.rts.ch%2Fx.jpg&format=jpg&width=480",
            video.posterUrl,
        )
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("searchResultMediaList?q=19h30"))
        assertTrue(request.path!!.contains("mediaType=VIDEO"))
    }
}
