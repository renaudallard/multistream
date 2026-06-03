package it.allard.multistream.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackedTitleEntity::class,
        EpisodeProgressEntity::class,
        SeriesProgressEntity::class,
        TitleProviderPrefEntity::class,
        CatalogCacheEntity::class,
        DetailCacheEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MultistreamDatabase : RoomDatabase() {
    abstract fun watchDao(): WatchDao
    abstract fun cacheDao(): CacheDao
}
