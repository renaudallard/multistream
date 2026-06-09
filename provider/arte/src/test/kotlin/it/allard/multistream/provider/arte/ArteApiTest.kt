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

    @Test fun browseGenre_mergesZonesFiltersExternalTeasersAndDedups() = runBlocking {
        // A genre page has no "listing" zone; programmes are merged from every zone, items without a
        // programId (external web-link teasers) are dropped, and duplicates across zones are collapsed.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"zones":[
                  {"code":"highlights_category_DOR","content":{"data":[
                    {"programId":"100-A","title":"Doc One","kind":{"isCollection":false}},
                    {"title":"Newsletter teaser","url":"https://www.arte.tv/fr/x"}
                  ]}},
                  {"code":"02_DOR","content":{"data":[
                    {"programId":"100-A","title":"Doc One","kind":{"isCollection":false}},
                    {"programId":"RC-200","title":"Doc Series","kind":{"isCollection":true}}
                  ]}}
                ]}
                """.trimIndent(),
            ),
        )
        val results = api.browseGenre("DOR", "fr")
        assertEquals(listOf("Doc One", "Doc Series"), results.map { it.title })
        assertEquals(MediaType.SERIES, results.first { it.title == "Doc Series" }.type)
        assertTrue(server.takeRequest().path!!.contains("/fr/web/pages/DOR/"))
    }
}
