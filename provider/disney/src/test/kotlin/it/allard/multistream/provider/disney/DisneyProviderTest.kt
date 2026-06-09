package it.allard.multistream.provider.disney

import it.allard.multistream.core.model.Genre
import it.allard.multistream.core.model.ProviderSecrets
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.net.buildClient
import it.allard.multistream.provider.api.ProviderConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DisneyProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: DisneyProvider

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().removeSuffix("/")
        provider = DisneyProvider(api = DisneyApi(client = buildClient(), webBase = base, apiBase = base))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun browseByGenre_queriesSearchWithFrenchGenreKeyword() = runBlocking {
        // Disney+ has no genre-set page; browse reuses the genre-aware search with the mapped keyword.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"data":{"page":{"containers":[{"items":[
                   {"id":"e1","actions":[{"infoBlock":"dXJuOmRzOmNtcDpldmE6bW92aWU="}],"visuals":{"title":"Soul"}}
                ]}]}}}""",
            ),
        )
        val config = ProviderConfig(Region("US"), enabled = true, secrets = ProviderSecrets(token = "FINAL"))
        val results = provider.browseByGenre(Genre.COMEDY, Region("US"), config)
        assertEquals(1, results.size)
        assertEquals("Soul", results[0].title)
        assertTrue(server.takeRequest().path!!.contains("/explore/v1.7/search?query=comedie"))
    }

    @Test fun browsableGenres_coverTheCanonicalKeywordSet() {
        val genres = provider.browsableGenres()
        assertTrue(genres.contains(Genre.COMEDY))
        assertTrue(genres.contains(Genre.HORROR))
        assertEquals(10, genres.size)
    }
}
