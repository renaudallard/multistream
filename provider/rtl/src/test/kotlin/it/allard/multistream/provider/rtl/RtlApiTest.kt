package it.allard.multistream.provider.rtl

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

    @Test fun getDetails_parsesSummaryYearAndCast() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(DETAIL))
        val d = api.getDetails("75cbca3b", ProviderRef(ProviderId.RTL, "75cbca3b", null))
        assertEquals("A copycat killer strikes.", d?.synopsis)
        assertEquals(2026, d?.year)
        assertEquals(listOf("Francis Huster", "Erika Sainte"), d?.cast)
        assertTrue(server.takeRequest().path!!.contains("/RTL_PLAY/detail3/75cbca3b"))
    }

    @Test fun getSeasons_parsesEpisodesAndStripsNumberPrefix() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(DETAIL))
        val seasons = api.getSeasons("75cbca3b")
        assertEquals(1, seasons.size)
        assertEquals(3, seasons[0].seasonNumber)
        assertEquals(2, seasons[0].episodes.size)
        assertEquals(1, seasons[0].episodes[0].episodeNumber)
        assertEquals("Le passé ressurgit", seasons[0].episodes[0].title) // "1. " prefix dropped
        assertEquals(52, seasons[0].episodes[0].runtimeMin) // 3133s / 60
    }

    private companion object {
        const val DETAIL =
            """{"title":{"label":"Zodiaque"},"description":"A copycat killer strikes.",
            "headerLabels":[{"label":"2026","accessibilityLabel":"Année de production"},
              {"label":"Thriller","accessibilityLabel":"Genre"}],
            "moreInfo":{"meta":[{"label":"Genre","items":[{"label":"Thriller","collectionId":"x"}]},
              {"label":"Rôles","items":[{"label":"Francis Huster","collectionId":null},{"label":"Erika Sainte","collectionId":null}]}]},
            "seasonPicker":{"indices":[3],"selected":{"index":3,"episodes":[
              {"index":1,"title":"1. Le passé ressurgit","description":"Ep one.","durationSeconds":3133},
              {"index":2,"title":"2. La traque","description":"Ep two.","durationSeconds":3000}]}}}"""
    }
}
