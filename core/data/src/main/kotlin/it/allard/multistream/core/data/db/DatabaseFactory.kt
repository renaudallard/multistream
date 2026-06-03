package it.allard.multistream.core.data.db

import android.content.Context
import androidx.room.Room

/** Builds the Room database, keeping the Room dependency inside :core:data. */
object DatabaseFactory {
    fun create(context: Context): MultistreamDatabase =
        Room.databaseBuilder(context.applicationContext, MultistreamDatabase::class.java, "multistream.db")
            .fallbackToDestructiveMigration()
            .build()
}
