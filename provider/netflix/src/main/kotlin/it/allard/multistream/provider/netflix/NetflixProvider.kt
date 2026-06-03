package it.allard.multistream.provider.netflix

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.provider.api.DeepLinks
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.StreamingProvider

/**
 * Netflix. Catalog search is behind MSL/Shakti and not attempted in v1, so this is launch + local
 * tracking only. Title deep links and an in-app search deep link are supported.
 */
class NetflixProvider : StreamingProvider {
    override val id = ProviderId.NETFLIX
    override val displayName = "Netflix"
    override val packageName = "com.netflix.mediaclient"
    override val capabilities = ProviderCapabilities(
        canDeepLinkToTitle = true,
        canInAppSearchDeepLink = true,
    )

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val titleId = ref.providerTitleId
        return Launcher.viewIntent(context, DeepLinks.netflixTitle(titleId), packageName)
            ?: Launcher.viewIntent(context, DeepLinks.netflixTitleScheme(titleId), packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? {
        if (!query.isNullOrBlank()) {
            Launcher.viewIntent(context, DeepLinks.netflixSearch(query), packageName)?.let { return it }
        }
        return Launcher.launchApp(context, packageName)
    }
}
