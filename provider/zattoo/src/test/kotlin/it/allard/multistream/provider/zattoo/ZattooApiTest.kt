package it.allard.multistream.provider.zattoo

import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.net.InMemoryCookieJar
import it.allard.multistream.core.net.buildClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ZattooApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ZattooApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = ZattooApi(client = buildClient(InMemoryCookieJar()), baseUrl = server.url("/").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun login_then_search_filtersProgramGuideByTitle() = runBlocking {
        // 1) homepage with the app token, 2) hello, 3) login, 4) power_guide
        server.enqueue(MockResponse().setBody("<html>var x; window.appToken = 'APPTOKEN'; more</html>"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":true}"""))
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"success":true,"session":{"loggedin":true,"power_guide_hash":"PH"}}"""),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"success":true,"channels":[
                  {"cid":"sf1","programs":[
                    {"id":1001,"t":"Tatort","s":1700000000,"e":1700005400},
                    {"id":1002,"t":"Tagesschau","s":1700005400,"e":1700007200}
                  ]},
                  {"cid":"zdf","programs":[{"id":2001,"t":"Tatort Wien","s":1700000000,"e":1700005400}]}
                ]}
                """.trimIndent(),
            ),
        )

        api.login("a@b.c", "pw")
        assertEquals("PH", api.powerHash)
        assertTrue(api.isLoggedIn())

        val results = api.search("tatort", Region.CH)
        assertEquals(2, results.size)
        assertTrue(results.all { it.type == MediaType.LIVE_PROGRAM })
        assertTrue(results.any { it.title == "Tatort" })
        assertTrue(results.any { it.title == "Tatort Wien" })

        // app token request hit the homepage; hello carried the token
        assertEquals("/", server.takeRequest().path)
        assertTrue(server.takeRequest().body.readUtf8().contains("client_app_token=APPTOKEN"))
    }
}
