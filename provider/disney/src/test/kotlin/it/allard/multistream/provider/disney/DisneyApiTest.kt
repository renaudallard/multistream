package it.allard.multistream.provider.disney

import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
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

class DisneyApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: DisneyApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().removeSuffix("/")
        api = DisneyApi(client = buildClient(), webBase = base, apiBase = base)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun login_runsBamgridFlow_andSwitchesProfile() = runBlocking {
        server.enqueue(MockResponse().setBody("""window.__PRELOAD__ = {"clientId":"disney-svod","clientApiKey":"CLIENTKEY"};"""))
        server.enqueue(jsonBody("""{"extensions":{"sdk":{"token":{"accessToken":"DEVTOK"}}}}"""))
        server.enqueue(
            jsonBody(
                """{"data":{"login":{"account":{"profiles":[
                   {"id":"P1","attributes":{"isDefault":true,"parentalControls":{"isPinProtected":false}}}
                ]}}},"extensions":{"sdk":{"token":{"accessToken":"NPTOK"}}}}""",
            ),
        )
        server.enqueue(jsonBody("""{"extensions":{"sdk":{"token":{"accessToken":"FINAL","refreshToken":"REF"}}}}"""))

        val tokens = api.login("a@b.c", "pw")
        assertEquals("FINAL", tokens.accessToken)
        assertEquals("REF", tokens.refreshToken)

        assertEquals("/", server.takeRequest().path) // homepage for client key
        assertTrue(server.takeRequest().path!!.contains("/graph/v1/device/graphql")) // registerDevice
        assertTrue(server.takeRequest().path!!.contains("/v1/public/graphql")) // login
        assertTrue(server.takeRequest().path!!.contains("/v1/public/graphql")) // switchProfile
    }

    @Test fun search_mapsExploreItems() = runBlocking {
        server.enqueue(
            jsonBody(
                """{"data":{"page":{"containers":[{"items":[
                   {"id":"e1","actions":[{"infoBlock":"dXJuOmRzOmNtcDpldmE6c2VyaWVz"}],"visuals":{"title":"Loki","metastringParts":{"releaseYearRange":{"startYear":2021}}}},
                   {"id":"e2","actions":[{"infoBlock":"dXJuOmRzOmNtcDpldmE6bW92aWU="}],"visuals":{"title":"Soul"}}
                ]}]}}}""",
            ),
        )
        val results = api.search("lo", "FINAL", Region("US"))
        assertEquals(2, results.size)
        val loki = results.first { it.title == "Loki" }
        assertEquals(2021, loki.year)
        assertEquals("https://www.disneyplus.com/browse/entity-e1", loki.ref.deepLinkHint)
        // the content type is decoded from the action infoBlock (eva:series vs eva:movie)
        assertEquals(MediaType.SERIES, loki.type)
        assertEquals(MediaType.MOVIE, results.first { it.title == "Soul" }.type)
    }

    @Test fun search_unauthorized_throwsAuthError() {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            runBlocking { api.search("x", "bad", Region("US")) }
            throw AssertionError("expected DisneyApiException")
        } catch (e: DisneyApiException) {
            assertTrue(e.authError)
        }
    }

    @Test fun getSeasons_parsesEntityAndSeasonPages() = runBlocking {
        server.enqueue(
            jsonBody(
                """{"data":{"page":{"containers":[
                   {"type":"details"},
                   {"type":"episodes","seasons":[{"id":"s1","visuals":{"name":"Season 1"}}]}
                ]}}}""",
            ),
        )
        server.enqueue(
            jsonBody(
                """{"data":{"season":{"items":[
                   {"visuals":{"episodeNumber":1,"episodeTitle":"Pilot","durationMs":1800000}},
                   {"visuals":{"episodeNumber":2,"episodeTitle":"Second","durationMs":1800000}}
                ]}}}""",
            ),
        )
        val seasons = api.getSeasons("e1", "FINAL")
        assertEquals(1, seasons.size)
        assertEquals(1, seasons[0].seasonNumber)
        assertEquals(2, seasons[0].episodes.size)
        assertEquals("Pilot", seasons[0].episodes[0].title)
        assertEquals(30, seasons[0].episodes[0].runtimeMin)
    }

    @Test fun getDetails_parsesSynopsisYearAndCast() = runBlocking {
        server.enqueue(
            jsonBody(
                """{"data":{"page":{
                   "visuals":{"title":"Loki","description":{"full":"A trickster god steps out of the shadow."},
                     "metastringParts":{"releaseYearRange":{"startYear":"2021"}}},
                   "containers":[
                     {"type":"episodes"},
                     {"type":"details","visuals":{"credits":[
                       {"heading":"Cast","items":[{"displayText":"Tom Hiddleston"},{"displayText":"Owen Wilson"}]},
                       {"heading":"Director","items":[{"displayText":"Kate Herron"}]}
                     ]}}
                   ]}}}""",
            ),
        )
        val details = api.getDetails("e1", "FINAL", ProviderRef(ProviderId.DISNEY, "e1", null))
        assertEquals(MediaType.SERIES, details?.type) // has an episodes container
        assertEquals("A trickster god steps out of the shadow.", details?.synopsis)
        assertEquals(2021, details?.year)
        assertEquals(listOf("Tom Hiddleston", "Owen Wilson"), details?.cast)
        assertTrue(server.takeRequest().path!!.contains("/explore/v1.9/page/entity-e1"))
    }

    @Test fun getDetails_typesAsMovieWithoutEpisodesContainer() = runBlocking {
        // A film has no `episodes` container; its synopsis/year live in the `details` container.
        server.enqueue(
            jsonBody(
                """{"data":{"page":{
                   "visuals":{"title":"Soul"},
                   "containers":[
                     {"type":"set","visuals":{"name":"SUGGESTIONS"}},
                     {"type":"details","visuals":{"description":{"full":"A jazz pianist finds his spark."},
                       "releaseYearRange":{"startYear":"2020"},
                       "credits":[{"heading":"Distribution :","items":[{"displayText":"Jamie Foxx"}]}]}}
                   ]}}}""",
            ),
        )
        val d = api.getDetails("m1", "FINAL", ProviderRef(ProviderId.DISNEY, "m1", null))
        assertEquals(MediaType.MOVIE, d?.type)
        assertEquals("A jazz pianist finds his spark.", d?.synopsis)
        assertEquals(2020, d?.year)
        assertEquals(listOf("Jamie Foxx"), d?.cast)
    }

    @Test fun fetchWatchedEpisodes_batchesPidsAndReadsProgress() = runBlocking {
        // Entity page yields the season refs; each season's items carry a personalization pid.
        server.enqueue(
            jsonBody(
                """{"data":{"page":{"containers":[
                   {"type":"episodes","seasons":[{"id":"s1","visuals":{"name":"Season 1"}}]}
                ]}}}""",
            ),
        )
        server.enqueue(
            jsonBody(
                """{"data":{"season":{"items":[
                   {"personalization":{"pid":"PID1"},"visuals":{"seasonNumber":"1","episodeNumber":"1","durationMs":1800000}},
                   {"personalization":{"pid":"PID2"},"visuals":{"seasonNumber":"1","episodeNumber":"2","durationMs":1800000}},
                   {"personalization":{"pid":"PID3"},"visuals":{"seasonNumber":"1","episodeNumber":"3","durationMs":1800000}}
                ]}}}""",
            ),
        )
        // Progress comes from the batch userState lookup: E1 finished (100), E2 part-watched (40),
        // E3 finished as a 0..1 fraction (0.97) which is scaled to a percentage.
        server.enqueue(
            jsonBody(
                """{"data":{"entityStates":{
                   "PID1":{"progress":{"progressPercentage":100}},
                   "PID2":{"progress":{"progressPercentage":40}},
                   "PID3":{"progress":{"progressPercentage":0.97}}
                }}}""",
            ),
        )
        val watched = api.fetchWatchedEpisodes("e1", "FINAL")
        assertEquals(setOf(EpisodeCoord(1, 1), EpisodeCoord(1, 3)), watched.toSet())
        assertTrue(server.takeRequest().path!!.contains("/explore/v1.9/page/entity-e1"))
        assertTrue(server.takeRequest().path!!.contains("/explore/v1.7/season/s1"))
        val userStateReq = server.takeRequest()
        assertEquals("POST", userStateReq.method)
        assertTrue(userStateReq.path!!.contains("/explore/v1.9/userState"))
        val body = userStateReq.body.readUtf8()
        assertTrue(body.contains("PID1") && body.contains("PID3"))
    }

    private fun jsonBody(body: String) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
