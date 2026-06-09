package it.allard.multistream.provider.toutv

import it.allard.multistream.core.model.EpisodeCoord
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
        val base = server.url("").toString().removeSuffix("/")
        api = ToutvApi(client = buildClient(), baseUrl = base, profilingUrl = base)
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

    @Test fun getSeasons_groupsLineupItemsBySeason() = runBlocking {
        server.enqueue(
            json(
                """
                {"content":[
                  {"title":"Épisodes","lineups":[
                    {"seasonNumber":1,"title":"Saison 1","items":[
                      {"episodeNumber":2,"title":"Deux","description":"d2","url":"show/s01e02","completionTime":1800000},
                      {"episodeNumber":1,"title":"Un","url":"show/s01e01","completionTime":3600000}
                    ]},
                    {"seasonNumber":2,"title":"Saison 2","items":[
                      {"episodeNumber":1,"title":"Retour","url":"show/s02e01"}
                    ]}
                  ]},
                  {"title":"Compléments","lineups":[{"seasonNumber":2,"items":[{"title":"Bonus","url":"show/extra"}]}]}
                ]}
                """.trimIndent(),
            ),
        )
        val seasons = api.getSeasons("show")
        assertEquals(listOf(1, 2), seasons.map { it.seasonNumber })
        assertEquals(listOf(1, 2), seasons[0].episodes.map { it.episodeNumber }) // ordered within the season
        assertEquals("Un", seasons[0].episodes[0].title)
        assertEquals(60, seasons[0].episodes[0].runtimeMin) // 3600000ms -> 60 min
        assertEquals(1, seasons[1].episodes.size) // the "Compléments" bonus (no episodeNumber) is dropped
        assertTrue(server.takeRequest().path!!.contains("/v2/toutv/show/show"))
    }

    @Test fun fetchWatchedEpisodes_marksSeasonUpToTheResumePoint() = runBlocking {
        // myview gives the one in-progress episode per show; the resume episode is unfinished, so the
        // episodes before it in that season are the watched ones.
        server.enqueue(
            json(
                """
                {"title":"Mes visionnements","items":[
                  {"title":"Autre","url":"autre/s02e05?lectureauto=1","completionStatus":{"completed":true}},
                  {"title":"Les Chefs!","url":"les-chefs/s14e08?lectureauto=1","completionStatus":{"completed":false,"seekTimePercentage":3}}
                ]}
                """.trimIndent(),
            ),
        )
        val watched = api.fetchWatchedEpisodes("les-chefs", "TOKEN")
        // S14 E1..E7 are watched (E8 is the in-progress resume episode).
        assertEquals((1..7).map { EpisodeCoord(14, it) }, watched)
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/v2/toutv/myview"))
        assertEquals("Bearer TOKEN", request.getHeader("Authorization"))
    }

    @Test fun browseByGenre_parsesCategoryShows() = runBlocking {
        // A genre category lists Show cards at content[].items.results[], same shape as search.
        server.enqueue(
            json(
                """
                {"content":[{"items":{"results":[
                  {"type":"Show","title":"Synchro","url":"synchro","infoTitle":"Comedie et humour | 1 saison","images":{"card":{"url":"https://images.tou.tv/(_Size_)/s.jpg"}}},
                  {"type":"Show","title":"Le daim","url":"le-daim","infoTitle":"Comedie | 1 h 30"},
                  {"type":"Section","title":"x","url":"section/x"}
                ]}}]}
                """.trimIndent(),
            ),
        )
        val results = api.browseByGenre("comedie-et-humour")
        assertEquals(2, results.size) // the Section card is skipped
        val synchro = results.first { it.title == "Synchro" }
        assertEquals(MediaType.SERIES, synchro.type) // "1 saison"
        assertEquals("synchro", synchro.ref.providerTitleId)
        assertEquals("https://ici.tou.tv/synchro", synchro.ref.deepLinkHint)
        assertEquals("https://images.tou.tv/360/s.jpg", synchro.posterUrl)
        assertEquals(MediaType.MOVIE, results.first { it.title == "Le daim" }.type) // no "saison"
        assertTrue(server.takeRequest().path!!.contains("/v2/toutv/category/comedie-et-humour"))
    }
}
