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
        // single-letter words are not articles: keep the English pronoun "I" and "A."/"L." initials
        assertEquals("i robot", normalizeTitle("I, Robot"))
        assertEquals("i am legend", normalizeTitle("I Am Legend"))
    }

    private fun result(
        provider: ProviderId,
        title: String,
        year: Int?,
        type: MediaType = MediaType.SERIES,
        imdb: String? = null,
        tmdbMovie: Long? = null,
    ) = UnifiedSearchResult(
        provider = provider,
        ref = ProviderRef(provider, "$provider-$title", region = Region.FR),
        title = title,
        type = type,
        year = year,
        externalIds = ExternalIds(imdb = imdb, tmdbMovie = tmdbMovie),
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

    @Test fun consecutive_years_do_not_chain_past_the_tolerance() {
        val merged = mergeResults(
            listOf(
                result(ProviderId.NETFLIX, "Solaris", 2019, MediaType.MOVIE),
                result(ProviderId.PRIME, "Solaris", 2020, MediaType.MOVIE),
                result(ProviderId.DISNEY, "Solaris", 2021, MediaType.MOVIE),
            )
        )
        // 2019 and 2020 merge against the 2019 anchor; 2021 is two years off it and stays separate.
        assertEquals(2, merged.size)
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

    @Test fun movie_and_series_same_title_no_year_get_distinct_keys() {
        // Regression: heuristic keys must include the media type, otherwise a movie and a series
        // with the same title and no year serialize to the same key and the results list crashes
        // with "Key ... was already used".
        val merged = mergeResults(
            listOf(
                result(ProviderId.MOLOTOV, "Lucifer", null, MediaType.MOVIE),
                result(ProviderId.NETFLIX, "Lucifer", null, MediaType.SERIES),
            )
        )
        assertEquals(2, merged.size)
        assertEquals(2, merged.map { it.key.serialize() }.distinct().size)
    }

    @Test fun heuristic_key_round_trips_through_serialize_and_parse() {
        val noYear = TitleKey.Heuristic(MediaType.SERIES, "lucifer", null)
        assertEquals(noYear, TitleKey.parse(noYear.serialize()))
        val withYear = TitleKey.Heuristic(MediaType.MOVIE, "spider man no way home", 2021)
        assertEquals(withYear, TitleKey.parse(withYear.serialize()))
    }

    @Test fun rankByRelevance_orders_full_matches_before_partial() {
        val titles = mergeResults(
            listOf(
                result(ProviderId.RTS, "Police Squad", 1982, MediaType.SERIES),
                result(ProviderId.NETFLIX, "Police Academy", 1984, MediaType.MOVIE),
                result(ProviderId.RTBF, "The Police Academy Story", 2000, MediaType.MOVIE),
                result(ProviderId.PRIME, "Police Academy 2", 1985, MediaType.MOVIE),
                result(ProviderId.DISNEY, "Naked Gun", 1988, MediaType.MOVIE),
            )
        )
        val ranked = rankByRelevance("police academy", titles).map { it.primaryTitle }
        assertEquals("Police Academy", ranked.first())
        assertTrue(ranked.indexOf("Police Academy 2") < ranked.indexOf("Police Squad"))
        assertTrue(ranked.indexOf("The Police Academy Story") < ranked.indexOf("Police Squad"))
        assertTrue(ranked.indexOf("Police Squad") < ranked.indexOf("Naked Gun"))
    }

    @Test fun rankByRelevance_tolerates_a_typo_in_the_query() {
        val titles = mergeResults(
            listOf(
                result(ProviderId.PRIME, "The Grand Tour", 2016, MediaType.SERIES),
                result(ProviderId.PRIME, "Clarkson's Farm", 2021, MediaType.SERIES),
            )
        )
        // "clarckson" (extra c) still ranks the title match first.
        assertEquals("Clarkson's Farm", rankByRelevance("clarckson", titles).map { it.primaryTitle }.first())
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

    @Test fun empty_normalized_titles_do_not_collapse() {
        val merged = mergeResults(
            listOf(
                result(ProviderId.NETFLIX, "!!!", 2020, MediaType.MOVIE),
                result(ProviderId.PRIME, "???", 2020, MediaType.MOVIE),
            )
        )
        assertEquals(2, merged.size)
    }

    @Test fun rows_sharing_only_a_secondary_id_merge() {
        val merged = mergeResults(
            listOf(
                result(ProviderId.PRIME, "Heat", 1995, type = MediaType.MOVIE, tmdbMovie = 949),
                result(ProviderId.NETFLIX, "Heat", 1995, type = MediaType.MOVIE, imdb = "tt0113277", tmdbMovie = 949),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(2, merged.first().availabilities.size)
    }
}
