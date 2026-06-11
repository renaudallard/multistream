package it.allard.multistream.core.data

import it.allard.multistream.core.data.db.TrackedTitleEntity
import it.allard.multistream.core.data.db.WatchStatus
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchRepositoryLogicTest {

    @Test fun rollupIsUnwatchedWhenNothingWatched() =
        assertEquals(WatchStatus.UNWATCHED, rollupStatus(last = null, next = EpisodeCoord(1, 1)))

    @Test fun rollupIsWatchedWhenNoNextEpisode() =
        assertEquals(WatchStatus.WATCHED, rollupStatus(last = EpisodeCoord(3, 10), next = null))

    @Test fun rollupIsWatchingWhenMoreToGo() =
        assertEquals(WatchStatus.WATCHING, rollupStatus(last = EpisodeCoord(1, 2), next = EpisodeCoord(1, 3)))

    @Test fun entityMapsToLibraryEntry() {
        val entity = TrackedTitleEntity(
            titleKey = "ext:imdb:tt0903747",
            primaryTitle = "Example Show",
            year = 2021,
            type = MediaType.SERIES.name,
            posterUrl = "https://x/p.jpg",
            status = WatchStatus.WATCHING.name,
            inWatchlist = true,
            addedAt = 1L,
            updatedAt = 2L,
        )
        val entry = entity.toEntry(nextSeason = 2, nextEpisode = 5)
        assertEquals("ext:imdb:tt0903747", entry.key.serialize())
        assertEquals("Example Show", entry.title)
        assertEquals(2021, entry.year)
        assertEquals(MediaType.SERIES, entry.type)
        assertEquals(WatchStatus.WATCHING, entry.status)
        assertEquals(true, entry.inWatchlist)
        assertEquals(2, entry.nextSeason)
        assertEquals(5, entry.nextEpisode)
    }
}
