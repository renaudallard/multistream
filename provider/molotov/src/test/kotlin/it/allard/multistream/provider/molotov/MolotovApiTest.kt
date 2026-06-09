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

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = MolotovApi(client = buildClient(), baseUrl = server.url("/").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun login_parsesTokens_andSendsAgentHeader() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"auth":{"access_token":"AT","refresh_token":"RT"},"account":{"id":42,"user_type":"premium"}}""",
            ),
        )
        val tokens = api.login("a@b.c", "pw")
        assertEquals("AT", tokens.accessToken)
        assertEquals("RT", tokens.refreshToken)
        assertEquals("42", tokens.userId)

        val request = server.takeRequest()
        assertEquals("/v3.1/auth/login", request.path)
        assertTrue(request.getHeader("X-Molotov-Agent")!!.contains("android_tv_app"))
    }

    @Test fun search_collectsTilesFromSections() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"sections":[{"title":"Results","items":[
                  {"type":"program","id":"p1","slug":"lupin","title":"Lupin","description":"A gentleman thief seeks revenge.","image_bundle":{"poster":{"medium":{"url":"https://img/lupin.jpg"}}}},
                  {"type":"vod","id":"v9","slug":"oss117","title":"OSS 117"},
                  {"type":"person","id":"x","title":"Some Actor"}
                ]}]}
                """.trimIndent(),
            ),
        )
        val results = api.search("lupin", "AT", Region.FR)
        assertEquals(2, results.size) // person is filtered out
        val lupin = results.first { it.title == "Lupin" }
        assertEquals(MediaType.SERIES, lupin.type)
        assertEquals("https://www.molotov.tv/lupin", lupin.ref.deepLinkHint)
        assertEquals("https://img/lupin.jpg", lupin.posterUrl)
        assertEquals("A gentleman thief seeks revenge.", lupin.synopsis)
        assertEquals(MediaType.MOVIE, results.first { it.title == "OSS 117" }.type)
    }

    @Test fun search_typesFilmProgramByCategory() = runBlocking {
        // A "program" tile is a film or a series; its metadata category (Films is id 1) decides which.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"sections":[{"items":[
                  {"type":"program","id":"p1","slug":"babas","title":"Les babas cool","metadata":{"program_category_id":"1","program_category":"Films"}},
                  {"type":"program","id":"p2","slug":"lupin","title":"Lupin","metadata":{"program_category_id":"2","program_category":"Séries"}}
                ]}]}""".trimIndent(),
            ),
        )
        val results = api.search("x", "AT", Region.FR)
        assertEquals(MediaType.MOVIE, results.first { it.title == "Les babas cool" }.type)
        assertEquals(MediaType.SERIES, results.first { it.title == "Lupin" }.type)
    }

    @Test fun search_doesNotCrashOnOversizedImageDimensions() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"sections":[{"items":[
                  {"type":"program","id":"p1","slug":"x","title":"X","image_bundle":{"poster":{"url":"https://img/99999999999x1/x.jpg"}}}
                ]}]}""".trimIndent(),
            ),
        )
        val results = api.search("x", "AT", Region.FR)
        assertEquals(1, results.size) // an oversized WxH must not throw and abort the result list
        assertEquals("https://img/99999999999x1/x.jpg", results.first().posterUrl)
    }

    @Test fun search_doesNotStackOverflowOnDeeplyNestedJson() = runBlocking {
        val n = 50_000
        val deep = "{\"items\":".repeat(n) + "0" + "}".repeat(n)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(deep))
        val results = api.search("x", "AT", Region.FR) // the depth cap must keep this from overflowing
        assertTrue(results.isEmpty())
    }

    @Test fun search_unauthorized_throwsAuthError() {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            runBlocking { api.search("x", "badtoken", Region.FR) }
            throw AssertionError("expected MolotovApiException")
        } catch (e: MolotovApiException) {
            assertTrue(e.authError)
        }
    }

    @Test fun browseByKind_fetchesGenreSectionAndParsesPrograms() = runBlocking {
        // The per-genre endpoint nests its tiles under `section` (singular), not `sections`/`items`.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"page":{},"section":{"slug":"kind_movies_1","items":[
                  {"type":"program","id":"343524","slug":"comme-chien-et-chat","title":"Comme chien et chat","metadata":{"program_category_id":"1"}},
                  {"type":"program","id":"6133831","slug":"le-jour","title":"Le Jour de la Colère","metadata":{"program_category_id":"1"}}
                ]},"sidebar":{}}
                """.trimIndent(),
            ),
        )
        val results = api.browseByKind("kind_movies_1", "AT", Region.FR)
        assertEquals(2, results.size)
        val film = results.first { it.title == "Comme chien et chat" }
        assertEquals(MediaType.MOVIE, film.type) // a program in the Films category is a movie
        assertEquals("https://www.molotov.tv/comme-chien-et-chat", film.ref.deepLinkHint)
        assertTrue(server.takeRequest().path!!.contains("/v2/categories/1/sections/kind_movies_1"))
    }
}
