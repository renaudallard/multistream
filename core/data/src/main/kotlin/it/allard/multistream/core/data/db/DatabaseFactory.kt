package it.allard.multistream.core.data.db

import android.content.Context
import androidx.room.Room

/** Builds the Room database, keeping the Room dependency inside :core:data. */
object DatabaseFactory {
    fun create(context: Context): MultistreamDatabase =
        Room.databaseBuilder(context.applicationContext, MultistreamDatabase::class.java, "multistream.db")
            // Destructive only on downgrade (rare). A schema upgrade must ship a Migration so the
            // user's watch history/watchlist/progress survive; without one Room throws rather than
            // silently wiping the whole DB (cache and user data share it).
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
}
