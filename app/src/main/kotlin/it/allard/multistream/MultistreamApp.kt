package it.allard.multistream

import android.app.Application
import it.allard.multistream.di.AppGraph

class MultistreamApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
