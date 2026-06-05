package it.allard.multistream.launch

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.provider.api.LaunchAction
import it.allard.multistream.provider.api.LaunchResolver
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.StreamingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves and fires launch intents, returning a short user-facing status message. */
class LaunchController(context: Context) {
    private val appContext = context.applicationContext

    // Intent resolution queries the PackageManager (binder IPC) and startActivity itself is not free,
    // so run them off the main thread; every intent carries FLAG_ACTIVITY_NEW_TASK and so can start
    // from a background thread.
    suspend fun launchTitle(
        provider: StreamingProvider,
        ref: ProviderRef,
        episode: EpisodeCoord? = null,
        query: String? = null,
    ): String = withContext(Dispatchers.IO) {
        when (val action = LaunchResolver.resolve(appContext, provider, ref, episode, query)) {
            is LaunchAction.Start ->
                if (startSafely(action.intent)) "Opening ${provider.displayName}…" else "Couldn't open ${provider.displayName}"
            is LaunchAction.Install -> {
                openStore(action.packageName)
                "${provider.displayName} isn't installed"
            }
            LaunchAction.Unavailable -> "Couldn't open ${provider.displayName}"
        }
    }

    /** Open the provider's app, optionally pre-loading a search query inside it. */
    suspend fun openApp(provider: StreamingProvider, query: String? = null): String = withContext(Dispatchers.IO) {
        if (!Launcher.isInstalled(appContext, provider.packageName)) {
            openStore(provider.packageName)
            return@withContext "${provider.displayName} isn't installed"
        }
        val intent = provider.launchAppFallback(appContext, query)
            ?: Launcher.launchApp(appContext, provider.packageName)
        if (intent != null && startSafely(intent)) {
            "Opening ${provider.displayName}…"
        } else {
            "Couldn't open ${provider.displayName}"
        }
    }

    private fun startSafely(intent: Intent): Boolean = try {
        appContext.startActivity(intent)
        true
    } catch (e: RuntimeException) {
        // A cross-app launch can fail several ways: no handler (ActivityNotFoundException), a target
        // that is not exported or needs a permission (SecurityException), or a malformed target
        // (IllegalArgumentException). Treat them all as a failed launch so the caller reports
        // "Couldn't open" instead of crashing.
        false
    }

    /** Open the app's store page: the Play Store app if it resolves, otherwise the web page. */
    private fun openStore(packageName: String) {
        if (!startSafely(Launcher.playStoreIntent(packageName))) {
            startSafely(Launcher.playStoreWebIntent(packageName))
        }
    }
}
