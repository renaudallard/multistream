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

    @Test fun search_keepsVideos_typesEpisodesAsSeriesAndFilmsAsMovies() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"total":3,"searchResultMediaList":[
                  {"urn":"urn:rts:video:15283680","mediaType":"VIDEO","type":"EPISODE","title":"Violences domestiques","show":{"title":"Temps présent"},"imageUrl":"https://img.rts.ch/x.jpg"},
                  {"urn":"urn:rts:video:42","mediaType":"VIDEO","type":"EPISODE","title":"Mourir peut attendre","show":{"title":"Film de minuit"}},
                  {"urn":"urn:rts:audio:abc","mediaType":"AUDIO","title":"Une radio","imageUrl":"https://img.rts.ch/a.jpg"}
                ]}
                """.trimIndent(),
            ),
        )
        val results = api.search("19h30")
        assertEquals(2, results.size) // the AUDIO item is skipped
        val episode = results.first { it.title == "Violences domestiques" }
        assertEquals(MediaType.SERIES, episode.type) // episode of a real show => series
        assertEquals("https://www.rts.ch/play/tv/redirect/detail/15283680", episode.ref.deepLinkHint)
        assertEquals(
            "https://il.srgssr.ch/images/?imageUrl=https%3A%2F%2Fimg.rts.ch%2Fx.jpg&format=jpg&width=480",
            episode.posterUrl,
        )
        // A film sits under a "Film"/"Cinéma" collection show => movie.
        assertEquals(MediaType.MOVIE, results.first { it.title == "Mourir peut attendre" }.type)
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("searchResultMediaList?q=19h30"))
        assertTrue(request.path!!.contains("mediaType=VIDEO"))
    }
}
