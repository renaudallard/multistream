package it.allard.multistream.provider.prime

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

class PrimeVideoProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: PrimeVideoProvider

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        provider = PrimeVideoProvider(
            api = PrimeApi(client = buildClient(), baseUrl = server.url("").toString().removeSuffix("/")),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun browseByGenre_queriesSearchWithEnglishGenreKeyword() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"results\":[{\"title\":\"Borat\",\"titleID\":\"amzn1.dv.gti.bbb\",\"entityType\":\"Movie\"}]}",
            ),
        )
        val config = ProviderConfig(Region("US"), enabled = true, secrets = ProviderSecrets(cookie = "at-main=tok"))
        val results = provider.browseByGenre(Genre.COMEDY, Region("US"), config)
        assertEquals(1, results.size)
        assertEquals("Borat", results[0].title)
        assertTrue(server.takeRequest().path!!.contains("/gp/video/search?phrase=comedy"))
    }

    @Test fun browsableGenres_coverTheCanonicalKeywordSet() {
        assertTrue(provider.browsableGenres().contains(Genre.HORROR))
        assertEquals(10, provider.browsableGenres().size)
    }
}
