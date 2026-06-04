package it.allard.multistream.provider.rtbf

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

class RtbfApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RtbfApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = RtbfApi(client = buildClient(), baseUrl = server.url("").toString().removeSuffix("/"))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun search_collectsProgramsAndVideosSkipsQuickLinks() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"status":200,"data":[
                  {"type":"QUICK_LINK","content":[{"label":"Documentaires","path":"/categorie/documentaires-31"}]},
                  {"type":"PROGRAM_LIST","content":[
                    {"id":"492","title":"Transversales","type":"SHOW","path":"/emission/transversales-492"}
                  ]},
                  {"type":"MEDIA_LIST","content":[
                    {"id":"3475510","title":"Les 24 heures moto","type":"VIDEO","path":"/media/les-24-heures-moto-3475510"}
                  ]}
                ]}
                """.trimIndent(),
            ),
        )
        val results = api.search("doc")
        assertEquals(2, results.size)
        val show = results.first { it.title == "Transversales" }
        assertEquals(MediaType.SERIES, show.type)
        assertEquals("https://auvio.rtbf.be/emission/transversales-492", show.ref.deepLinkHint)
        assertEquals(MediaType.MOVIE, results.first { it.title == "Les 24 heures moto" }.type)
        assertTrue(server.takeRequest().path!!.contains("/search?query=doc"))
    }
}
