package it.allard.multistream.core.data

import androidx.room.withTransaction
import it.allard.multistream.core.data.db.EpisodeProgressEntity
import it.allard.multistream.core.data.db.MultistreamDatabase
import it.allard.multistream.core.data.db.SeriesProgressEntity
import it.allard.multistream.core.data.db.TitleProviderPrefEntity
import it.allard.multistream.core.data.db.TrackedTitleEntity
import it.allard.multistream.core.data.db.WatchStatus
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Title
import it.allard.multistream.core.model.TitleKey
import it.allard.multistream.core.model.computeNextEpisode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Public, Room-free view of a tracked title for the UI. */
data class LibraryEntry(
    val key: TitleKey,
    val title: String,
    val year: Int?,
    val type: MediaType,
    val posterUrl: String?,
    val status: WatchStatus,
    val inWatchlist: Boolean,
    val nextSeason: Int? = null,
    val nextEpisode: Int? = null,
)

/**
 * Local, provider-independent watch tracking. Keyed on [TitleKey] so progress survives a title
 * appearing or disappearing from any given provider.
 */
class WatchRepository(private val db: MultistreamDatabase) {
    private val dao = db.watchDao()

    fun continueWatching(): Flow<List<LibraryEntry>> =
        dao.continueWatching().map { rows -> rows.map { it.title.toEntry(it.nextSeason, it.nextEpisode) } }

    fun watchlist(): Flow<List<LibraryEntry>> =
        dao.watchlist().map { list -> list.map { it.toEntry() } }

    fun history(): Flow<List<LibraryEntry>> =
        dao.history().map { list -> list.map { it.toEntry() } }

    fun observeStatus(key: TitleKey): Flow<WatchStatus?> =
        dao.observeTitle(key.serialize()).map { it?.let { e -> WatchStatus.valueOf(e.status) } }

    fun observeInWatchlist(key: TitleKey): Flow<Boolean> =
        dao.observeTitle(key.serialize()).map { it?.inWatchlist ?: false }

    fun observeWatchedEpisodes(key: TitleKey): Flow<Set<EpisodeCoord>> =
        dao.observeEpisodes(key.serialize()).map { list ->
            list.filter { it.watched }.map { EpisodeCoord(it.season, it.episode) }.toSet()
        }

    suspend fun ensureTracked(title: Title) {
        val key = title.key.serialize()
        val now = now()
        // INSERT OR IGNORE creates the row only if absent, atomically, so a concurrent ensureTracked
        // for a brand-new title can't reset a status or watchlist flag the other caller just set.
        dao.insertTitleIfAbsent(
            TrackedTitleEntity(
                titleKey = key,
                primaryTitle = title.primaryTitle,
                year = title.year,
                type = title.type.name,
                posterUrl = title.posterUrl,
                status = WatchStatus.UNWATCHED.name,
                inWatchlist = false,
                addedAt = now,
                updatedAt = now,
            ),
        )
        // Persist where the title can be launched so Library can open it after a process restart,
        // when the in-memory search index is gone.
        if (title.availabilities.isNotEmpty()) {
            dao.upsertProviderPrefs(
                title.availabilities.map { a ->
                    TitleProviderPrefEntity(
                        titleKey = key,
                        provider = a.provider.name,
                        providerTitleId = a.ref.providerTitleId,
                        deepLinkHint = a.ref.deepLinkHint,
                        preferred = false,
                    )
                },
            )
        }
    }

    /** The stored launch target for a tracked title, for opening it after the index is gone. */
    suspend fun launchRef(key: TitleKey): ProviderRef? =
        dao.providerPrefs(key.serialize()).firstNotNullOfOrNull { pref ->
            // Resolve the stored provider name safely: an unknown name (e.g. a provider removed in a
            // later version) must not throw and crash the Library "Open" coroutine.
            ProviderId.entries.firstOrNull { it.name == pref.provider }?.let { provider ->
                ProviderRef(
                    provider = provider,
                    providerTitleId = pref.providerTitleId,
                    deepLinkHint = pref.deepLinkHint,
                )
            }
        }

    suspend fun setInWatchlist(title: Title, inList: Boolean) {
        ensureTracked(title)
        dao.setWatchlist(title.key.serialize(), inList, now())
    }

    /** Movies are tracked as a whole (no per-episode rows). */
    suspend fun setMovieWatched(title: Title, watched: Boolean) {
        ensureTracked(title)
        val status = if (watched) WatchStatus.WATCHED else WatchStatus.UNWATCHED
        dao.setStatus(title.key.serialize(), status.name, now())
    }

    /** Mark an episode watched/unwatched and recompute the series rollup + next-episode pointer. */
    suspend fun setEpisodeWatched(title: Title, coord: EpisodeCoord, watched: Boolean) = db.withTransaction {
        ensureTracked(title)
        val key = title.key.serialize()
        val now = now()
        dao.upsertEpisode(
            EpisodeProgressEntity(
                titleKey = key,
                season = coord.season,
                episode = coord.episode,
                watched = watched,
                watchedAt = if (watched) now else null,
            ),
        )
        val last = dao.lastWatched(key)?.let { EpisodeCoord(it.season, it.episode) }
        val next = computeNextEpisode(last, title.seasons)
        dao.upsertSeriesProgress(
            SeriesProgressEntity(
                titleKey = key,
                lastWatchedSeason = last?.season,
                lastWatchedEpisode = last?.episode,
                nextSeason = next?.season,
                nextEpisode = next?.episode,
                lastActivityAt = now,
            ),
        )
        dao.setStatus(key, rollupStatus(last, next).name, now)
    }

    private fun now() = System.currentTimeMillis()
}

/**
 * Roll a series up to a single status from its watch progress: nothing watched is [WatchStatus.UNWATCHED],
 * no episode left to watch is [WatchStatus.WATCHED], anything in between is [WatchStatus.WATCHING].
 */
internal fun rollupStatus(last: EpisodeCoord?, next: EpisodeCoord?): WatchStatus = when {
    last == null -> WatchStatus.UNWATCHED
    next == null -> WatchStatus.WATCHED
    else -> WatchStatus.WATCHING
}

internal fun TrackedTitleEntity.toEntry(nextSeason: Int? = null, nextEpisode: Int? = null) = LibraryEntry(
    key = TitleKey.parse(titleKey),
    title = primaryTitle,
    year = year,
    type = MediaType.valueOf(type),
    posterUrl = posterUrl,
    status = WatchStatus.valueOf(status),
    inWatchlist = inWatchlist,
    nextSeason = nextSeason,
    nextEpisode = nextEpisode,
)
