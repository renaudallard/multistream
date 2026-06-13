package it.allard.multistream.core.data.db

import android.content.Context
import androidx.room.Room

/** Builds the Room database, keeping the Room dependency inside :core:data. */
object DatabaseFactory {
    fun create(context: Context): MultistreamDatabase =
        Room.databaseBuilder(context.applicationContext, MultistreamDatabase::class.java, "multistream.db")
            // No migrations are shipped: the only persisted data is local watch tracking, which is
            // re-derivable by re-marking, so a schema change recreates the DB instead of carrying
            // (and testing) migration code. Acceptable while the user base is tiny.
            .fallbackToDestructiveMigration()
            .build()
}
