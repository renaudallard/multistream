package it.allard.multistream.provider.molotov

import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.net.NetJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MolotovParserTest {
    private fun parse(json: String) = MolotovParser.parsePage(NetJson.parseToJsonElement(json), Region.FR)

    @Test fun takesLiveCardTitleFromFooter_andPosterCardTitleFromTrackingParam() {
        val results = parse(
            """
            {"content":{"sections":[{"component_type":"card-poster","components":[
              {"id":"a","footer":{"title":{"text":"Le 20h"}},"image":{"url":"https://i/a.jpg"},
               "actions":{"on_click":[{"endpoint":{"url":"/papi/v1/program-details/channel/600019"}}]}},
              {"id":"b","picture":{"url":"https://i/b.jpg"},
               "actions":{"on_click":[{"endpoint":{"url":"/papi/v1/program-details/program/VOD_7?trkOriginElement=Un%20Film"}}]}}
            ]}]}}
            """.trimIndent(),
        )
        assertEquals(2, results.size)
        val live = results.first { it.ref.providerTitleId == "channel:600019" }
        assertEquals("Le 20h", live.title)
        assertEquals(MediaType.LIVE_CHANNEL, live.type)
        val poster = results.first { it.ref.providerTitleId == "program:VOD_7" }
        assertEquals("Un Film", poster.title) // decoded from trkOriginElement
    }

    @Test fun dedupesTheSameTitleAcrossChannels() {
        val results = parse(
            """
            {"content":{"sections":[{"components":[
              {"title":{"text":"Plus belle la vie"},
               "actions":{"on_click":[{"endpoint":{"url":"/papi/v1/program-details/program/VOD_1"}}]}},
              {"title":{"text":"Plus belle la vie"},
               "actions":{"on_click":[{"endpoint":{"url":"/papi/v1/program-details/program/VOD_2"}}]}}
            ]}]}}
            """.trimIndent(),
        )
        assertEquals(1, results.size)
        assertEquals("program:VOD_1", results.first().ref.providerTitleId) // first one kept
    }

    @Test fun numbersEpisodesSequentiallyWhenTitlesCarryNoCoordinates() {
        val seasons = MolotovParser.parseSeasons(
            NetJson.parseToJsonElement(
                """
                {"content":{"sections":[{"component_type":"list-item-wide","components":[
                  {"title":{"text":"Premier volet"},
                   "actions":{"on_click":[{"endpoint":{"url":"/papi/v1/program-details/program/VOD_a"}}]}},
                  {"title":{"text":"Deuxième volet"},
                   "actions":{"on_click":[{"endpoint":{"url":"/papi/v1/program-details/program/VOD_b"}}]}}
                ]}]}}
                """.trimIndent(),
            ),
        )
        assertEquals(1, seasons.size)
        assertEquals(1, seasons.first().seasonNumber)
        assertEquals(listOf(1, 2), seasons.first().episodes.map { it.episodeNumber })
        assertEquals("Premier volet", seasons.first().episodes.first().title)
    }

    @Test fun parsesSaisonEpisodeFrenchCoordinates() {
        val seasons = MolotovParser.parseSeasons(
            NetJson.parseToJsonElement(
                """
                {"content":{"sections":[{"component_type":"list-item-wide","components":[
                  {"title":{"text":"Saison 2 Épisode 7 - Le secret"},
                   "actions":{"on_click":[{"endpoint":{"url":"/papi/v1/program-details/program/VOD_c"}}]}}
                ]}]}}
                """.trimIndent(),
            ),
        )
        assertEquals(2, seasons.single().seasonNumber)
        assertEquals(7, seasons.single().episodes.single().episodeNumber)
        assertEquals("Le secret", seasons.single().episodes.single().title)
    }

    @Test fun ignoresCardsWithoutADeepLinkableAction() {
        val results = parse(
            """
            {"content":{"sections":[{
              "aux_button":{"actions":{"on_click":[{"type":"navigation","endpoint":{"url":"/papi/v1/page/live-tv"}}]}},
              "components":[
                {"title":{"text":"See all"},"actions":{"on_click":[{"endpoint":{"url":"/papi/v1/page/films"}}]}}
              ]}]}}
            """.trimIndent(),
        )
        assertNull(results.firstOrNull())
    }
}
