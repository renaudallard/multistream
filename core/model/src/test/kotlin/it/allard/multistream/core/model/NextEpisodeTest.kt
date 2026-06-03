package it.allard.multistream.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextEpisodeTest {

    private val series = listOf(
        Season(1, episodes = (1..3).map { Episode(1, it) }),
        Season(2, episodes = (1..2).map { Episode(2, it) }),
    )

    @Test fun nothing_watched_starts_at_s1e1() {
        assertEquals(EpisodeCoord(1, 1), computeNextEpisode(null, series))
    }

    @Test fun mid_season_advances_one() {
        assertEquals(EpisodeCoord(1, 2), computeNextEpisode(EpisodeCoord(1, 1), series))
    }

    @Test fun end_of_season_rolls_to_next_season() {
        assertEquals(EpisodeCoord(2, 1), computeNextEpisode(EpisodeCoord(1, 3), series))
    }

    @Test fun finale_watched_returns_null() {
        assertNull(computeNextEpisode(EpisodeCoord(2, 2), series))
    }

    @Test fun stale_pointer_returns_next_known_episode() {
        // Watched S1E5 (no longer in the list) => next greater coordinate is S2E1.
        assertEquals(EpisodeCoord(2, 1), computeNextEpisode(EpisodeCoord(1, 5), series))
    }

    @Test fun empty_series_has_no_next() {
        assertNull(computeNextEpisode(null, emptyList()))
    }
}
