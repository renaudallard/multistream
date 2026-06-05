package it.allard.multistream.provider.rtl

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

class RtlApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RtlApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = RtlApi(client = buildClient(), baseUrl = server.url("/").toString().removeSuffix("/"))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun search_flattensTeasersAndBuildsDeepLink() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"userMessage":null,"results":[
                  {"type":"exact","title":"Programmes","teasers":[
                    {"title":"Zodiaque","detailId":"75cbca3b","genre":"Drame","imageUrl":"https://img/zod.webp"},
                    {"title":"Appel d'urgence","detailId":"22edb355","imageUrl":"https://img/appel.webp"}
                  ]}
                ]}
                """.trimIndent(),
            ),
        )
        val results = api.search("zodiaque", Region("BE"))
        assertEquals(2, results.size)
        val zod = results.first { it.title == "Zodiaque" }
        assertEquals(MediaType.SERIES, zod.type)
        assertEquals("https://img/zod.webp", zod.posterUrl)
        assertEquals("https://www.rtlplay.be/rtlplay/zodiaque~75cbca3b", zod.ref.deepLinkHint)
        // apostrophe dropped, accents/spaces hyphenated (d'urgence -> durgence)
        assertEquals(
            "https://www.rtlplay.be/rtlplay/appel-durgence~22edb355",
            results.first { it.title == "Appel d'urgence" }.ref.deepLinkHint,
        )
        assertTrue(server.takeRequest().path!!.contains("/RTL_PLAY/search?query=zodiaque"))
    }
}
