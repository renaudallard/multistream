package it.allard.multistream.provider.toutv

import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.net.buildClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToutvApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ToutvApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = ToutvApi(client = buildClient(), baseUrl = server.url("").toString().removeSuffix("/"))
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun search_typesShows_skipsSections_confirmsAmbiguousFromDetail() = runBlocking {
        server.enqueue(
            json(
                """
                {"results":[
                  {"type":"Show","title":"District 31","url":"district-31","infoTitle":"Crime et police | 6 saisons","images":{"card":{"url":"https://images.tou.tv/(_Size_)/d31.jpg"}}},
                  {"type":"Show","title":"Infoman 2025","url":"infoman-2025","infoTitle":"Comedie | 64 min"},
                  {"type":"Section","title":"En direct","url":"section/en-direct"}
                ]}
                """.trimIndent(),
            ),
        )
        // "Infoman 2025" has no "saison", so its type is confirmed from the show detail @type (a series
        // here, even though the duration subtitle looked like a film).
        server.enqueue(json("""{"structuredMetadata":{"@type":"TVSeries"}}"""))
        val results = api.search("d")
        assertEquals(2, results.size) // the Section row is skipped
        val d31 = results.first { it.title == "District 31" }
        assertEquals(MediaType.SERIES, d31.type) // "6 saisons" => series, no detail call
        assertEquals("https://ici.tou.tv/district-31", d31.ref.deepLinkHint)
        assertEquals("https://images.tou.tv/360/d31.jpg", d31.posterUrl) // (_Size_) filled
        // The duration-style subtitle is overridden by the authoritative @type.
        assertEquals(MediaType.SERIES, results.first { it.title == "Infoman 2025" }.type)
        assertTrue(server.takeRequest().path!!.contains("/v2/toutv/search?term=d"))
        assertTrue(server.takeRequest().path!!.contains("/v2/toutv/show/infoman-2025")) // only the ambiguous one
    }

    @Test fun getDetails_readsTypeYearSynopsisAndCast() = runBlocking {
        server.enqueue(
            json(
                """
                {"title":"Le Confessionnal","description":"Un homme revient a Quebec.",
                 "structuredMetadata":{"@type":"Movie","datePublished":"1995-09-01T04:00:00Z",
                   "abstract":"ignored when description is present","actor":[{"name":"Lothaire Bluteau"},{"name":"Patrick Goyette"}]},
                 "images":{"card":{"url":"https://images.tou.tv/(_Size_)/conf.jpg"}}}
                """.trimIndent(),
            ),
        )
        val ref = ProviderRef(ProviderId.TOUTV, "le-confessionnal", "https://ici.tou.tv/le-confessionnal")
        val details = api.getDetails("le-confessionnal", ref)
        assertEquals(MediaType.MOVIE, details?.type) // schema.org @type = Movie
        assertEquals(1995, details?.year)
        assertEquals("Un homme revient a Quebec.", details?.synopsis) // top-level description wins
        assertEquals(listOf("Lothaire Bluteau", "Patrick Goyette"), details?.cast)
        assertTrue(server.takeRequest().path!!.contains("/v2/toutv/show/le-confessionnal"))
    }
}
