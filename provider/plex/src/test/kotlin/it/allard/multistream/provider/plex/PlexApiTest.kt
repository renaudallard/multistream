package it.allard.multistream.provider.plex

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
import org.junit.Assert.fail
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
            pinsUrl = server.url("/pins").toString(),
            resourcesUrl = server.url("/resources").toString(),
            discoverUrl = server.url("/search").toString(),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun createPin_returnsIdAndCode() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"id":12345,"code":"WXYZ"}"""))
        val pin = api.createPin()
        assertEquals("12345", pin.id)
        assertEquals("WXYZ", pin.code)
        assertTrue(server.takeRequest().path!!.contains("/pins"))
    }

    @Test fun signIn_returnsAuthToken() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"authToken":"TKN-9"}"""))
        assertEquals("TKN-9", api.signIn("a@b.c", "pw"))
        assertTrue(server.takeRequest().body.readUtf8().contains("login=a%40b.c"))
    }

    @Test fun connectServer_returnsFirstReachableConnection() = runBlocking {
        val base = server.url("/").toString().removeSuffix("/")
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[{"provides":"server","owned":true,"accessToken":"SRV-TOK","connections":[{"uri":"$base","local":true,"relay":false}]}]""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"MediaContainer":{}}"""))
        val connection = api.connectServer("ACCT")
        assertEquals(base, connection?.first)
        assertEquals("SRV-TOK", connection?.second)
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

    @Test fun searchServer_parsesHubMetadata() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"MediaContainer":{"Hub":[
                  {"type":"movie","Metadata":[{"title":"The Batman","type":"movie","year":2022,"ratingKey":"42","thumb":"/library/metadata/42/thumb/1"}]},
                  {"type":"show","Metadata":[{"title":"Gotham","type":"show","ratingKey":"77"}]}
                ]}}
                """.trimIndent(),
            ),
        )
        val base = server.url("/").toString().removeSuffix("/")
        val results = api.searchServer(base, "TKN", "batman")
        assertEquals(2, results.size)
        assertEquals(2022, results.first { it.title == "The Batman" }.year)
        // The poster URL carries no token; PlexImageAuth adds X-Plex-Token as a header at load time.
        assertEquals("$base/library/metadata/42/thumb/1", results.first { it.title == "The Batman" }.posterUrl)
        assertEquals(MediaType.SERIES, results.first { it.title == "Gotham" }.type)
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/hubs/search?query=batman"))
        assertEquals("TKN", request.getHeader("X-Plex-Token"))
    }

    @Test fun getDetails_parsesSummaryYearAndCast() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"MediaContainer":{"Metadata":[{"title":"The Batman","type":"movie","year":2022,"summary":"A detective hunts a killer.","Role":[{"tag":"Robert Pattinson"},{"tag":"Zoe Kravitz"}]}]}}""",
            ),
        )
        val base = server.url("/").toString().removeSuffix("/")
        val details = api.getDetails(base, "TKN", "42", ProviderRef(ProviderId.PLEX, "42", null))
        assertEquals("A detective hunts a killer.", details?.synopsis)
        assertEquals(2022, details?.year)
        assertEquals(listOf("Robert Pattinson", "Zoe Kravitz"), details?.cast)
        assertTrue(server.takeRequest().path!!.contains("/library/metadata/42"))
    }

    @Test fun fetchWatchedEpisodes_returnsOnlyViewedEpisodes() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"MediaContainer":{"Metadata":[
                  {"type":"episode","parentIndex":1,"index":1,"viewCount":1,"viewOffset":null},
                  {"type":"episode","parentIndex":1,"index":2,"viewCount":2},
                  {"type":"episode","parentIndex":1,"index":3,"viewOffset":500},
                  {"type":"episode","parentIndex":2,"index":1,"viewCount":0}
                ]}}
                """.trimIndent(),
            ),
        )
        val base = server.url("/").toString().removeSuffix("/")
        val watched = api.fetchWatchedEpisodes(base, "TKN", "99")
        assertEquals(2, watched.size)
        assertEquals(EpisodeCoord(1, 1), watched[0])
        assertEquals(EpisodeCoord(1, 2), watched[1])
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/library/metadata/99/allLeaves"))
        assertEquals("TKN", request.getHeader("X-Plex-Token"))
    }

    @Test fun getSeasons_groupsEpisodesBySeason() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"MediaContainer":{"Metadata":[
                  {"type":"episode","ratingKey":"201","parentIndex":2,"index":1,"parentTitle":"Season 2","title":"Return","summary":"They come back.","duration":2700000},
                  {"type":"episode","ratingKey":"102","parentIndex":1,"index":2,"parentTitle":"Season 1","title":"Second","duration":1800000},
                  {"type":"episode","ratingKey":"101","parentIndex":1,"index":1,"parentTitle":"Season 1","title":"Pilot","summary":"It begins."}
                ]}}
                """.trimIndent(),
            ),
        )
        val base = server.url("/").toString().removeSuffix("/")
        val seasons = api.getSeasons(base, "TKN", "99")
        assertEquals(2, seasons.size)
        // Seasons are ordered, episodes within a season are ordered by number.
        assertEquals(1, seasons[0].seasonNumber)
        assertEquals("Season 1", seasons[0].title)
        assertEquals(listOf(1, 2), seasons[0].episodes.map { it.episodeNumber })
        val pilot = seasons[0].episodes[0]
        assertEquals("Pilot", pilot.title)
        assertEquals("It begins.", pilot.synopsis)
        assertEquals(ProviderRef(ProviderId.PLEX, "101"), pilot.providerRefs.single())
        // 1800000 ms / 60000 = 30 minutes.
        assertEquals(30, seasons[0].episodes[1].runtimeMin)
        assertEquals(2, seasons[1].seasonNumber)
        assertEquals("Return", seasons[1].episodes.single().title)
        assertEquals(45, seasons[1].episodes.single().runtimeMin)
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/library/metadata/99/allLeaves"))
        assertEquals("TKN", request.getHeader("X-Plex-Token"))
    }

    @Test fun verifyServer_failsOnUnauthorizedToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val base = server.url("/").toString().removeSuffix("/")
        try {
            api.verifyServer(base, "BAD")
            fail("expected PlexApiException")
        } catch (e: PlexApiException) {
            assertTrue(e.authError)
        }
    }
}
