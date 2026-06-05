package it.allard.multistream.core.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Series-level rollup status for a tracked title. */
enum class WatchStatus { UNWATCHED, WATCHING, WATCHED }

@Entity(tableName = "tracked_title")
data class TrackedTitleEntity(
    @PrimaryKey val titleKey: String,
    val primaryTitle: String,
    val year: Int?,
    val type: String,
    val posterUrl: String?,
    val status: String,
    val inWatchlist: Boolean,
    val addedAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "episode_progress",
    primaryKeys = ["titleKey", "season", "episode"],
    foreignKeys = [
        ForeignKey(
            entity = TrackedTitleEntity::class,
            parentColumns = ["titleKey"],
            childColumns = ["titleKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("titleKey")],
)
data class EpisodeProgressEntity(
    val titleKey: String,
    val season: Int,
    val episode: Int,
    val watched: Boolean,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val watchedAt: Long? = null,
)

@Entity(
    tableName = "series_progress",
    foreignKeys = [
        ForeignKey(
            entity = TrackedTitleEntity::class,
            parentColumns = ["titleKey"],
            childColumns = ["titleKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SeriesProgressEntity(
    @PrimaryKey val titleKey: String,
    val lastWatchedSeason: Int?,
    val lastWatchedEpisode: Int?,
    val nextSeason: Int?,
    val nextEpisode: Int?,
    val lastActivityAt: Long,
)

@Entity(
    tableName = "title_provider_pref",
    primaryKeys = ["titleKey", "provider"],
    foreignKeys = [
        ForeignKey(
            entity = TrackedTitleEntity::class,
            parentColumns = ["titleKey"],
            childColumns = ["titleKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("titleKey")],
)
data class TitleProviderPrefEntity(
    val titleKey: String,
    val provider: String,
    val providerTitleId: String,
    val deepLinkHint: String?,
    val preferred: Boolean,
)

/** Join row for the Continue-Watching feed (tracked title + denormalized next-episode pointer). */
data class ContinueWatchingRow(
    @Embedded val title: TrackedTitleEntity,
    val nextSeason: Int?,
    val nextEpisode: Int?,
)
