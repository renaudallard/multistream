package it.allard.multistream.provider.disney

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
 * Disney+. Catalog search (bamgrid GraphQL) lands in a later milestone; v1 is launch + local
 * tracking. The app verifies https://www.disneyplus.com app links, so title deep links route in.
 */
class DisneyProvider : StreamingProvider {
    override val id = ProviderId.DISNEY
    override val displayName = "Disney+"
    override val packageName = "com.disney.disneyplus"
    override val capabilities = ProviderCapabilities(
        canDeepLinkToTitle = true,
    )

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: DeepLinks.disneyEntity(ref.providerTitleId)
        return Launcher.viewIntent(context, url, packageName)
            ?: Launcher.viewIntent(context, DeepLinks.disneyScheme(ref.providerTitleId), packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
