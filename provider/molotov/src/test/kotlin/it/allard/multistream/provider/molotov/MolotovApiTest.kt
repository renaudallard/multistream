package it.allard.multistream.provider.molotov

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

class MolotovApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: MolotovApi
    private val auth = MolotovAuth("AT", "u1", "p1")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = MolotovApi(client = buildClient(), baseUrl = server.url("/").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun signin_parsesTokens_andSendsMolotovTenantHeader() = runBlocking {
        server.enqueue(json("""{"access_token":"AT","refresh_token":"RT","id_token":"IT"}"""))
        val tokens = api.signin("a@b.c", "pw")
        assertEquals("AT", tokens.accessToken)
        assertEquals("RT", tokens.refreshToken)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/signin", request.path)
        assertEquals("molotov", request.getHeader("x-application-id"))
        assertTrue(request.body.readUtf8().contains("a@b.c"))
    }

    @Test fun fetchUser_parsesAccountAndProfileIds() = runBlocking {
        server.enqueue(json("""{"data":{"id":"u1","profiles":[{"id":"p1","name":"Moi"}]}}"""))
        val user = api.fetchUser("AT")
        assertEquals("u1", user.userId)
        assertEquals("p1", user.profileId)
        assertEquals("Bearer AT", server.takeRequest().getHeader("authorization"))
    }

    @Test fun refresh_sendsRefreshTokenAsBearer() = runBlocking {
        server.enqueue(json("""{"access_token":"NEW","refresh_token":"RT2"}"""))
        val tokens = api.refresh("RT")
        assertEquals("NEW", tokens.accessToken)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/refresh", request.path)
        assertEquals("Bearer RT", request.getHeader("authorization"))
    }

    @Test fun search_mapsFuboCardsToEtincelleDeepLinks() = runBlocking {
        server.enqueue(
            json(
                """
                {"title":{"text":"Résultats"},"content":{"template":"catalog","sections":[
                  {"title":{"text":"Résultats"},"component_type":"card-wide","components":[
                    {"id":"c1","title":{"text":"Lupin"},"picture":{"url":"https://img/lupin.jpg"},
                     "actions":{"on_click":[{"type":"navigation","endpoint":{"url":"https://api-eu.fubo.tv/papi/v1/program-details/series/lupin","method":"GET"}}]}},
                    {"id":"c2","title":{"text":"OSS 117"},"picture":{"url":"https://img/oss.jpg"},
                     "actions":{"on_click":[{"endpoint":{"url":"https://api-eu.fubo.tv/papi/v1/program-details/program/VOD_42","method":"GET"}}]}},
                    {"id":"c3","title":{"text":"France 2"},"picture":{"url":"https://img/f2.png"},
                     "actions":{"on_click":[{"endpoint":{"url":"https://api-eu.fubo.tv/papi/v1/program-details/channel/600019","method":"GET"}}]}},
                    {"id":"c4","title":{"text":"Some Actor"}}
                  ]}
                ]}}
                """.trimIndent(),
            ),
        )
        val results = api.search("lupin", auth, Region.FR)
        assertEquals(3, results.size) // the card with no deep-link action is dropped

        val lupin = results.first { it.title == "Lupin" }
        assertEquals(MediaType.SERIES, lupin.type)
        assertEquals("series:lupin", lupin.ref.providerTitleId)
        assertEquals("etincelle://series/lupin", lupin.ref.deepLinkHint)
        assertEquals("https://img/lupin.jpg", lupin.posterUrl)

        val oss = results.first { it.title == "OSS 117" }
        assertEquals(MediaType.MOVIE, oss.type)
        assertEquals("program:VOD_42", oss.ref.providerTitleId)
        assertEquals("etincelle://program/VOD_42", oss.ref.deepLinkHint)

        val france2 = results.first { it.title == "France 2" }
        assertEquals(MediaType.LIVE_CHANNEL, france2.type)
        assertEquals("channel:600019", france2.ref.providerTitleId)
        assertEquals("etincelle://channel/600019", france2.ref.deepLinkHint)

        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/papi/v1/search?query=lupin"))
        assertEquals("Bearer AT", request.getHeader("authorization"))
        assertEquals("p1", request.getHeader("x-profile-id"))
    }

    @Test fun getSeasons_parsesWatchNowEpisodes_andSkipsRecommendations() = runBlocking {
        server.enqueue(
            json(
                """
                {"content":{"sections":[
                  {"component_type":"list-item-wide","components":[
                    {"title":{"text":"S1 E1 La Maison Perruque"},"subtitle":{"text":"22m"},"body":{"picture":{"url":"https://i/1.jpg"}},
                     "actions":{"on_click":[{"endpoint":{"url":"/papi/v1/program-details/program/VOD_1"}}]}},
                    {"title":{"text":"S1 E2 Le Crime d'Ariane"},"subtitle":{"text":"22m"},
                     "actions":{"on_click":[{"endpoint":{"url":"/papi/v1/program-details/program/VOD_2"}}]}},
                    {"title":{"text":"S2 E1 Nouvelle saison"},
                     "actions":{"on_click":[{"endpoint":{"url":"/papi/v1/program-details/program/VOD_3"}}]}}
                  ]},
                  {"component_type":"carousel","title":{"text":"À voir aussi"},"components":[
                    {"title":{"text":"Autre série"},
                     "actions":{"on_click":[{"endpoint":{"url":"/papi/v1/program-details/series/autre"}}]}}
                  ]}
                ]}}
                """.trimIndent(),
            ),
        )
        val seasons = api.getSeasons("100005706", auth)
        assertEquals(listOf(1, 2), seasons.map { it.seasonNumber })
        val season1 = seasons.first()
        assertEquals(listOf(1, 2), season1.episodes.map { it.episodeNumber })
        // The real shape is "S1 E1 <name>" in the title (no separator); the coordinates are stripped.
        assertEquals("La Maison Perruque", season1.episodes.first().title)
        assertEquals("https://i/1.jpg", season1.episodes.first().stillUrl) // thumbnail from body.picture
        assertEquals(listOf(1), seasons.last().episodes.map { it.episodeNumber }) // the recommendation is dropped

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/papi/v1/program-details/series/100005706"))
        assertTrue(request.path!!.contains("tabID=id-tab-watch-now"))
    }

    @Test fun search_unauthorized_throwsAuthError() {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            runBlocking { api.search("x", auth, Region.FR) }
            throw AssertionError("expected MolotovApiException")
        } catch (e: MolotovApiException) {
            assertTrue(e.authError)
        }
    }

    @Test fun search_doesNotStackOverflowOnDeeplyNestedJson() = runBlocking {
        val n = 50_000
        val deep = "{\"items\":".repeat(n) + "0" + "}".repeat(n)
        server.enqueue(json(deep))
        val results = api.search("x", auth, Region.FR) // the depth cap must keep this from overflowing
        assertTrue(results.isEmpty())
    }

    private fun json(body: String) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
