package it.allard.multistream.launch

import android.content.Context
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.provider.api.LaunchAction
import it.allard.multistream.provider.api.LaunchResolver
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.StreamingProvider

/** Resolves and fires launch intents, returning a short user-facing status message. */
class LaunchController(context: Context) {
    private val appContext = context.applicationContext

    fun launchTitle(
        provider: StreamingProvider,
        ref: ProviderRef,
        episode: EpisodeCoord? = null,
        query: String? = null,
    ): String = when (val action = LaunchResolver.resolve(appContext, provider, ref, episode, query)) {
        is LaunchAction.Start -> {
            appContext.startActivity(action.intent)
            "Opening ${provider.displayName}…"
        }
        is LaunchAction.Install -> {
            appContext.startActivity(action.intent)
            "${provider.displayName} isn't installed"
        }
        LaunchAction.Unavailable -> "Couldn't open ${provider.displayName}"
    }

    /** Open the provider's app, optionally pre-loading a search query inside it. */
    fun openApp(provider: StreamingProvider, query: String? = null): String {
        if (!Launcher.isInstalled(appContext, provider.packageName)) {
            appContext.startActivity(Launcher.playStoreIntent(provider.packageName))
            return "${provider.displayName} isn't installed"
        }
        val intent = provider.launchAppFallback(appContext, query)
            ?: Launcher.launchApp(appContext, provider.packageName)
        return if (intent != null) {
            appContext.startActivity(intent)
            "Opening ${provider.displayName}…"
        } else {
            "Couldn't open ${provider.displayName}"
        }
    }
}
