package it.allard.multistream.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconcileTest {

    @Test fun normalize_strips_article_punctuation_and_diacritics() {
        assertEquals("mandalorian", normalizeTitle("The Mandalorian"))
        assertEquals("bureau des legendes", normalizeTitle("Le Bureau des Légendes"))
        assertEquals("spider man no way home", normalizeTitle("Spider-Man: No Way Home"))
        assertEquals("squid game", normalizeTitle("  Squid   Game  "))
    }

    private fun result(
        provider: ProviderId,
        title: String,
        year: Int?,
        type: MediaType = MediaType.SERIES,
        imdb: String? = null,
    ) = UnifiedSearchResult(
        provider = provider,
        ref = ProviderRef(provider, "$provider-$title", region = Region.FR),
        title = title,
        type = type,
        year = year,
        externalIds = ExternalIds(imdb = imdb),
    )

    @Test fun same_show_on_two_providers_merges_with_two_availabilities() {
        val merged = mergeResults(
            listOf(
                result(ProviderId.DISNEY, "The Mandalorian", 2019),
                result(ProviderId.NETFLIX, "Mandalorian", 2019),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(2, merged.first().availabilities.size)
    }

    @Test fun remakes_with_distant_years_stay_separate() {
        val merged = mergeResults(
            listOf(
                result(ProviderId.NETFLIX, "Dune", 1984, MediaType.MOVIE),
                result(ProviderId.PRIME, "Dune", 2021, MediaType.MOVIE),
            )
        )
        assertEquals(2, merged.size)
    }

    @Test fun streaming_vs_release_year_skew_still_merges() {
        val merged = mergeResults(
            listOf(
                result(ProviderId.DISNEY, "Andor", 2022, MediaType.SERIES),
                result(ProviderId.NETFLIX, "Andor", 2023, MediaType.SERIES),
            )
        )
        assertEquals(1, merged.size)
    }

    @Test fun movie_and_series_with_same_title_do_not_merge() {
        val merged = mergeResults(
            listOf(
                result(ProviderId.NETFLIX, "Fargo", 1996, MediaType.MOVIE),
                result(ProviderId.DISNEY, "Fargo", 2014, MediaType.SERIES),
            )
        )
        assertEquals(2, merged.size)
    }

    @Test fun external_id_match_merges_despite_title_difference() {
        val merged = mergeResults(
            listOf(
                result(ProviderId.NETFLIX, "Money Heist", 2017, imdb = "tt6468322"),
                result(ProviderId.DISNEY, "La Casa de Papel", 2017, imdb = "tt6468322"),
            )
        )
        assertEquals(1, merged.size)
        assertTrue(merged.first().key is TitleKey.External)
    }
}
