package it.allard.multistream.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTitleIfAbsent(title: TrackedTitleEntity)

    @Upsert suspend fun upsertEpisode(episode: EpisodeProgressEntity)

    @Upsert suspend fun upsertSeriesProgress(progress: SeriesProgressEntity)

    @Upsert suspend fun upsertProviderPrefs(prefs: List<TitleProviderPrefEntity>)

    @Query("SELECT * FROM title_provider_pref WHERE titleKey = :key ORDER BY provider")
    suspend fun providerPrefs(key: String): List<TitleProviderPrefEntity>

    @Query("UPDATE tracked_title SET inWatchlist = :inList, updatedAt = :ts WHERE titleKey = :key")
    suspend fun setWatchlist(key: String, inList: Boolean, ts: Long)

    @Query("UPDATE tracked_title SET status = :status, updatedAt = :ts WHERE titleKey = :key")
    suspend fun setStatus(key: String, status: String, ts: Long)

    @Query("SELECT * FROM tracked_title WHERE titleKey = :key")
    fun observeTitle(key: String): Flow<TrackedTitleEntity?>

    @Query("SELECT * FROM episode_progress WHERE titleKey = :key ORDER BY season, episode")
    fun observeEpisodes(key: String): Flow<List<EpisodeProgressEntity>>

    @Query(
        "SELECT * FROM episode_progress WHERE titleKey = :key AND watched = 1 " +
            "ORDER BY season DESC, episode DESC LIMIT 1",
    )
    suspend fun lastWatched(key: String): EpisodeProgressEntity?

    @Query("SELECT * FROM tracked_title WHERE inWatchlist = 1 ORDER BY addedAt DESC")
    fun watchlist(): Flow<List<TrackedTitleEntity>>

    @Query("SELECT * FROM tracked_title ORDER BY updatedAt DESC")
    fun history(): Flow<List<TrackedTitleEntity>>

    @Query(
        "SELECT t.*, sp.nextSeason AS nextSeason, sp.nextEpisode AS nextEpisode " +
            "FROM tracked_title t JOIN series_progress sp ON t.titleKey = sp.titleKey " +
            "WHERE sp.nextEpisode IS NOT NULL AND t.status = 'WATCHING' " +
            "ORDER BY sp.lastActivityAt DESC",
    )
    fun continueWatching(): Flow<List<ContinueWatchingRow>>
}
