package it.allard.multistream.provider.prime

import it.allard.multistream.core.model.EpisodeCoord
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

    @Test fun search_doesNotStackOverflowOnDeeplyNestedJson() = runBlocking {
        val n = 50_000
        val deep = "[".repeat(n) + "]".repeat(n)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(deep))
        val results = api.search("x", "at-main=tok", Region("US")) // depth cap must prevent overflow
        assertTrue(results.isEmpty())
    }

    @Test fun getSeasons_walksTemplateJsonForEpisodes() = runBlocking {
        // The detail page embeds episode-shaped nodes carrying a season + episode number and some
        // descriptive fields; parseSeasons groups them by season and orders them.
        server.enqueue(
            MockResponse().setBody(
                "<html><body>" +
                    "<script type=\"text/template\">" +
                    "{\"detail\":{\"episodes\":[" +
                    "{\"seasonNumber\":1,\"episodeNumber\":2,\"title\":\"Cherry\",\"synopsis\":\"Hughie meets Butcher.\",\"runtime\":60,\"gti\":\"amzn1.dv.gti.e2\"}," +
                    "{\"seasonNumber\":1,\"episodeNumber\":1,\"title\":\"The Name of the Game\",\"durationMs\":3600000}" +
                    "]}}" +
                    "</script></body></html>",
            ),
        )
        val seasons = api.getSeasons("amzn1.dv.gti.series", "at-main=tok")
        assertEquals(1, seasons.size)
        val season = seasons.first()
        assertEquals(1, season.seasonNumber)
        // Episodes are ordered by number even though the page listed E2 before E1.
        assertEquals(listOf(1, 2), season.episodes.map { it.episodeNumber })
        val e1 = season.episodes.first()
        assertEquals("The Name of the Game", e1.title)
        assertEquals(60, e1.runtimeMin) // 3600000ms -> 60min
        val e2 = season.episodes[1]
        assertEquals("Cherry", e2.title)
        assertEquals("Hughie meets Butcher.", e2.synopsis)
        assertEquals(60, e2.runtimeMin)
        assertEquals("amzn1.dv.gti.e2", e2.providerRefs.single().providerTitleId)
    }

    @Test fun getSeasons_defaultsToSeasonOneWhenSeasonAbsent() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                "<html><body><script type=\"text/template\">" +
                    "{\"episodeList\":[{\"episodeNumber\":1,\"title\":\"Pilot\"}]}" +
                    "</script></body></html>",
            ),
        )
        val seasons = api.getSeasons("gti", "at-main=tok")
        assertEquals(1, seasons.size)
        assertEquals(1, seasons.first().seasonNumber)
        assertEquals("Pilot", seasons.first().episodes.single().title)
    }

    private fun hydrationPage(json: String) =
        "<html><body><script id=\"dv-web-page-hydration-data\" data-testid=\"hydration-data\" type=\"application/json\">" +
            json + "</script></body></html>"

    @Test fun getSeasons_readsHydrationAndFetchesEverySeason() = runBlocking {
        // A modern detail page embeds only the selected season (here S2) and a selector listing every
        // season's detail link; getSeasons fetches the other seasons' links and merges the full run.
        server.enqueue(
            MockResponse().setBody(
                hydrationPage(
                    "{\"seasons\":[" +
                        "{\"seasonLink\":\"/detail/S2LINK/ref=s2\",\"displayName\":\"Season 2\",\"sequenceNumber\":2,\"isSelected\":true}," +
                        "{\"seasonLink\":\"/detail/S1LINK/ref=s1\",\"displayName\":\"Season 1\",\"sequenceNumber\":1,\"isSelected\":false}]," +
                        "\"episodeList\":{\"cardTitleIds\":[\"amzn1.dv.gti.s2e1\",\"amzn1.dv.gti.s2e2\"]}," +
                        "\"detail\":{" +
                        "\"amzn1.dv.gti.season2\":{\"titleType\":\"season\",\"seasonNumber\":2}," +
                        "\"amzn1.dv.gti.s2e1\":{\"titleType\":\"episode\",\"episodeNumber\":1,\"title\":\"S2E1\",\"synopsis\":\"first\",\"duration\":1800,\"images\":{\"covershot\":\"http://img/s2e1.jpg\"}}," +
                        "\"amzn1.dv.gti.s2e2\":{\"titleType\":\"episode\",\"episodeNumber\":2,\"title\":\"S2E2\",\"duration\":2400}}}",
                ),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                hydrationPage(
                    "{\"seasons\":[" +
                        "{\"seasonLink\":\"/detail/S1LINK/ref=s1\",\"displayName\":\"Season 1\",\"sequenceNumber\":1,\"isSelected\":true}]," +
                        "\"episodeList\":{\"cardTitleIds\":[\"amzn1.dv.gti.s1e1\",\"amzn1.dv.gti.s1e2\"]}," +
                        "\"detail\":{" +
                        "\"amzn1.dv.gti.s1e1\":{\"titleType\":\"episode\",\"episodeNumber\":1,\"title\":\"S1E1\",\"duration\":1500}," +
                        "\"amzn1.dv.gti.s1e2\":{\"titleType\":\"episode\",\"episodeNumber\":2,\"title\":\"S1E2\",\"duration\":1500}}}",
                ),
            ),
        )
        val seasons = api.getSeasons("amzn1.dv.gti.show", "at-main=tok")
        // Both seasons present and ordered, even though only S2 was inline on the first page.
        assertEquals(listOf(1, 2), seasons.map { it.seasonNumber })
        assertEquals("Season 1", seasons[0].title)
        assertEquals(listOf(1, 2), seasons[0].episodes.map { it.episodeNumber })
        val s2e1 = seasons[1].episodes.first()
        assertEquals("S2E1", s2e1.title)
        assertEquals("first", s2e1.synopsis)
        assertEquals(30, s2e1.runtimeMin) // duration 1800s -> 30 min
        assertEquals("http://img/s2e1.jpg", s2e1.stillUrl)
        assertEquals("amzn1.dv.gti.s2e1", s2e1.providerRefs.single().providerTitleId)
        // The first request is the title page; the second fetches the non-selected season's link.
        assertTrue(server.takeRequest().path!!.contains("/detail/amzn1.dv.gti.show"))
        assertTrue(server.takeRequest().path!!.contains("/detail/S1LINK"))
    }

    @Test fun fetchWatchedEpisodes_readsProgressAcrossEverySeason() = runBlocking {
        // S2 (selected): episode 1 fully watched (progress 1.0), episode 2 only part-watched (0.3).
        server.enqueue(
            MockResponse().setBody(
                hydrationPage(
                    "{\"seasons\":[" +
                        "{\"seasonLink\":\"/detail/S2LINK/ref=s2\",\"displayName\":\"Season 2\",\"sequenceNumber\":2,\"isSelected\":true}," +
                        "{\"seasonLink\":\"/detail/S1LINK/ref=s1\",\"displayName\":\"Season 1\",\"sequenceNumber\":1,\"isSelected\":false}]," +
                        "\"detail\":{" +
                        "\"amzn1.dv.gti.s2e1\":{\"titleType\":\"episode\",\"episodeNumber\":1}," +
                        "\"amzn1.dv.gti.s2e2\":{\"titleType\":\"episode\",\"episodeNumber\":2}}," +
                        "\"action\":{" +
                        "\"amzn1.dv.gti.s2e1\":{\"primaryActions\":[{\"payload\":{\"playback\":{\"progress\":1,\"resumeTime\":0}}}]}," +
                        "\"amzn1.dv.gti.s2e2\":{\"primaryActions\":[{\"payload\":{\"playback\":{\"progress\":0.3,\"resumeTime\":500}}}]}}}",
                ),
            ),
        )
        // S1 (the other season's page): episode 3 fully watched.
        server.enqueue(
            MockResponse().setBody(
                hydrationPage(
                    "{\"seasons\":[{\"seasonLink\":\"/detail/S1LINK/ref=s1\",\"displayName\":\"Season 1\",\"sequenceNumber\":1,\"isSelected\":true}]," +
                        "\"detail\":{\"amzn1.dv.gti.s1e3\":{\"titleType\":\"episode\",\"episodeNumber\":3}}," +
                        "\"action\":{\"amzn1.dv.gti.s1e3\":{\"primaryActions\":[{\"payload\":{\"playback\":{\"progress\":1}}}]}}}",
                ),
            ),
        )
        val watched = api.fetchWatchedEpisodes("amzn1.dv.gti.show", "at-main=tok")
        // The fully-watched episodes of both seasons, and not the part-watched S2E2.
        assertEquals(setOf(EpisodeCoord(2, 1), EpisodeCoord(1, 3)), watched.toSet())
        assertTrue(server.takeRequest().path!!.contains("/detail/amzn1.dv.gti.show"))
        assertTrue(server.takeRequest().path!!.contains("/detail/S1LINK"))
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

    @Test fun browseGenre_keepsEveryResultAndQueriesKeyword() = runBlocking {
        // Genre browse must not apply the search title-substring filter: a genre keyword never matches the
        // titles it returns, so every parsed result is kept (unlike search, which filters by title).
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"results\":[" +
                    "{\"title\":\"Mr. Bean\",\"titleID\":\"amzn1.dv.gti.aaa\",\"entityType\":\"TV Show\"}," +
                    "{\"title\":\"Borat\",\"titleID\":\"amzn1.dv.gti.bbb\",\"entityType\":\"Movie\"}" +
                    "]}",
            ),
        )
        val results = api.browseGenre("comedy", "at-main=tok", Region("US"))
        assertEquals(setOf("Mr. Bean", "Borat"), results.map { it.title }.toSet())
        assertTrue(server.takeRequest().path!!.contains("/gp/video/search?phrase=comedy"))
    }
}
