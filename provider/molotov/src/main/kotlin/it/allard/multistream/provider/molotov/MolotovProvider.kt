package it.allard.multistream.provider.molotov

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.StreamingProvider

/**
 * Molotov (French live TV + replay). Its api.molotov.tv JSON catalog is the first full-search
 * target (M1); for now this is launch + local tracking. Deep links use the molotov:// scheme /
 * app.molotov.tv app links carried as a [ProviderRef.deepLinkHint].
 */
class MolotovProvider : StreamingProvider {
    override val id = ProviderId.MOLOTOV
    override val displayName = "Molotov"
    override val packageName = "tv.molotov.app"
    override val capabilities = ProviderCapabilities(
        canDeepLinkToTitle = true,
        isLiveTv = true,
        requiresRegion = true,
        requiresAuth = true,
    )

    override fun supportedRegions(): Set<Region> = setOf(Region.FR)

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val hint = ref.deepLinkHint ?: return null
        return Launcher.viewIntent(context, hint, packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
