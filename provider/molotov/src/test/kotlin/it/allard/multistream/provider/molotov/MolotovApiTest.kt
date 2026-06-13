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
                  {"type":"program","id":"p1","slug":"lupin","title":"Lupin","description":"A gentleman thief seeks revenge.","metadata":{"channel_id":"3","program_id":"4242"},"image_bundle":{"poster":{"medium":{"url":"https://img/lupin.jpg"}}}},
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
        // The new app accepts no external program deep link, so launch just opens the app.
        assertEquals(null, lupin.ref.deepLinkHint)
        assertEquals("3:4242", lupin.ref.providerTitleId) // channel:program, for the episode list
        // A tile without channel/program metadata keeps the slug id (and still no deep link).
        val oss = results.first { it.title == "OSS 117" }
        assertEquals("oss117", oss.ref.providerTitleId)
        assertEquals(null, oss.ref.deepLinkHint)
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
                  {"type":"program","id":"343524","slug":"comme-chien-et-chat","title":"Comme chien et chat","metadata":{"program_category_id":"1","channel_id":"42","program_id":"343524"}},
                  {"type":"program","id":"6133831","slug":"le-jour","title":"Le Jour de la Colère","metadata":{"program_category_id":"1"}}
                ]},"sidebar":{}}
                """.trimIndent(),
            ),
        )
        val results = api.browseByKind("kind_movies_1", "AT", Region.FR)
        assertEquals(2, results.size)
        val film = results.first { it.title == "Comme chien et chat" }
        assertEquals(MediaType.MOVIE, film.type) // a program in the Films category is a movie
        assertEquals("42:343524", film.ref.providerTitleId) // channel:program, for the episode list
        assertEquals(null, film.ref.deepLinkHint)
        assertTrue(server.takeRequest().path!!.contains("/v2/categories/1/sections/kind_movies_1"))
    }

    @Test fun getSeasons_groupsEpisodesBySeason_andSkipsOtherPrograms() = runBlocking {
        // Episode tiles carry season/episode coordinates as strings in metadata (real payload shape).
        // Recommendation tiles for other programs are dropped whether they carry a different
        // program_id or (a cross-promo) none at all.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"sections":[{"slug":"episodes","items":[
                  {"type":"program","title":"Les as de la jungle","subtitle_formatter":{"format":"S03E47 - La fugitive"},
                   "metadata":{"channel_id":"18","program_id":"19460","season_number":"3","episode_number":"47","episode_title":"La fugitive"}},
                  {"type":"program","title":"Les as de la jungle","subtitle_formatter":{"format":"S03E46 - Le retour"},
                   "metadata":{"channel_id":"18","program_id":"19460","season_number":"3","episode_number":"46","episode_title":"Le retour"}},
                  {"type":"program","title":"Les as de la jungle","subtitle_formatter":{"format":"S01E01 - Origines"},
                   "metadata":{"channel_id":"18","program_id":"19460","season_number":"1","episode_number":"1"}},
                  {"type":"program","title":"Autre programme",
                   "metadata":{"channel_id":"18","program_id":"99999","season_number":"1","episode_number":"5","episode_title":"Pas le bon"}},
                  {"type":"program","title":"Reco sans id",
                   "metadata":{"channel_id":"42","season_number":"1","episode_number":"9","episode_title":"Reco"}}
                ]}]}
                """.trimIndent(),
            ),
        )
        val seasons = api.getSeasons("18", "19460", "AT")
        assertEquals(listOf(1, 3), seasons.map { it.seasonNumber })
        assertEquals(listOf(46, 47), seasons.last().episodes.map { it.episodeNumber })
        assertEquals("La fugitive", seasons.last().episodes.last().title)
        assertEquals("Origines", seasons.first().episodes.first().title) // from the SxxEyy subtitle
        // The untagged cross-promo episode (S1E9) must not pollute season 1.
        assertEquals(listOf(1), seasons.first().episodes.map { it.episodeNumber })
        assertTrue(server.takeRequest().path!!.contains("/v2/channels/18/programs/19460/view"))
    }

    @Test fun getSeasons_listsEpisodesEvenWhenNoneCarryProgramId() = runBlocking {
        // Fallback: if the program's own episode tiles omit program_id, list them anyway rather than
        // returning nothing.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"sections":[{"items":[
                  {"type":"program","title":"X","metadata":{"season_number":"1","episode_number":"1","episode_title":"Un"}},
                  {"type":"program","title":"X","metadata":{"season_number":"1","episode_number":"2","episode_title":"Deux"}}
                ]}]}
                """.trimIndent(),
            ),
        )
        val seasons = api.getSeasons("18", "19460", "AT")
        assertEquals(1, seasons.size)
        assertEquals(listOf(1, 2), seasons.first().episodes.map { it.episodeNumber })
    }

    @Test fun getSeasons_collectsEpisodesNumberedOnlyInTheSubtitle() = runBlocking {
        // An episode tile whose number lives only in the "SxxEyy" subtitle (no metadata.episode_number)
        // is still collected and parsed.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"sections":[{"items":[
                  {"type":"program","title":"X","subtitle_formatter":{"format":"S02E04 - Titre"},
                   "metadata":{"program_id":"19460"}}
                ]}]}
                """.trimIndent(),
            ),
        )
        val seasons = api.getSeasons("18", "19460", "AT")
        assertEquals(2, seasons.single().seasonNumber)
        assertEquals(listOf(4), seasons.single().episodes.map { it.episodeNumber })
    }

    @Test fun getSeasons_fallsBackToSubtitleCoordinates() = runBlocking {
        // Coordinates can be absent from metadata; the "SxxEyy" subtitle then provides them.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"sections":[{"items":[
                  {"type":"program","title":"X","subtitle_formatter":{"format":"S02E03 - Plume-baguette"},
                   "metadata":{"program_id":"7","episode_number":"3"}}
                ]}]}
                """.trimIndent(),
            ),
        )
        val seasons = api.getSeasons("1", "7", "AT")
        assertEquals(1, seasons.size)
        assertEquals(2, seasons.first().seasonNumber)
        assertEquals("Plume-baguette", seasons.first().episodes.single().title)
    }
}
