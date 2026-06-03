package it.allard.multistream.provider.disney

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
                   {"id":"e1","visuals":{"title":"Loki","metastringParts":{"releaseYearRange":{"startYear":2021}}}},
                   {"id":"e2","visuals":{"title":"Soul"}}
                ]}]}}}""",
            ),
        )
        val results = api.search("lo", "FINAL", Region("US"))
        assertEquals(2, results.size)
        val loki = results.first { it.title == "Loki" }
        assertEquals(2021, loki.year)
        assertEquals("https://www.disneyplus.com/browse/entity-e1", loki.ref.deepLinkHint)
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

    private fun jsonBody(body: String) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
