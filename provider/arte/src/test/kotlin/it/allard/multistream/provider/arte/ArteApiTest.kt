package it.allard.multistream.provider.arte

import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.net.buildClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArteApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ArteApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = ArteApi(client = buildClient(), baseUrl = server.url("").toString().removeSuffix("/"))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun search_parsesListingZoneAndIgnoresBoutique() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"zones":[
                  {"code":"listing_SEARCH","content":{"data":[
                    {"programId":"RC-024364","title":"Sources","url":"https://www.arte.tv/fr/videos/RC-024364/sources/","kind":{"code":"MAGAZINE","isCollection":true}},
                    {"programId":"012345-000-A","title":"Le Film","url":"https://www.arte.tv/fr/videos/012345-000-A/le-film/","kind":{"code":"MOVIE","isCollection":false}}
                  ]}},
                  {"code":"boutique_SEARCH","content":{"data":[{"programId":"X","title":"DVD","kind":{"isCollection":false}}]}}
                ]}
                """.trimIndent(),
            ),
        )
        val results = api.search("doc", "fr")
        assertEquals(2, results.size)
        val sources = results.first { it.title == "Sources" }
        assertEquals(MediaType.SERIES, sources.type)
        assertEquals("https://www.arte.tv/fr/videos/RC-024364/sources/", sources.ref.deepLinkHint)
        assertEquals(MediaType.MOVIE, results.first { it.title == "Le Film" }.type)
        assertTrue(server.takeRequest().path!!.contains("/fr/web/pages/SEARCH/?query=doc"))
    }
}
