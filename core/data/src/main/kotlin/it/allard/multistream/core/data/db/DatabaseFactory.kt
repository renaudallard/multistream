package it.allard.multistream.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Builds the Room database, keeping the Room dependency inside :core:data. */
object DatabaseFactory {
    fun create(context: Context): MultistreamDatabase =
        Room.databaseBuilder(context.applicationContext, MultistreamDatabase::class.java, "multistream.db")
            .addMigrations(MIGRATION_1_2)
            // Destructive only on downgrade (rare). A schema upgrade must ship a Migration so the
            // user's watch history/watchlist/progress survive; without one Room throws rather than
            // silently wiping the whole DB.
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    // v2 drops the unused catalog/detail cache tables; the watch-tracking tables are untouched.
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS catalog_cache")
            db.execSQL("DROP TABLE IF EXISTS detail_cache")
        }
    }
}
