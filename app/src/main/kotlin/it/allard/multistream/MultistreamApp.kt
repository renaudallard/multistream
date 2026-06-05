package it.allard.multistream

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import it.allard.multistream.di.AppGraph
import it.allard.multistream.provider.plex.PlexImageAuth

class MultistreamApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    // Load images through a client that adds the Plex server token as a request header, so the token
    // is never part of a poster URL that would land in the database or Coil's on-disk cache.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { PlexImageAuth.imageClient() }
            .build()
}
