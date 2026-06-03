package it.allard.multistream.provider.zattoo

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
 * Zattoo (DACH live TV + replay). The zapi catalog supports full search (M1), but the app's
 * manifest only exposes `zattoo://zattoo.com` with no title path, so title-level deep linking is
 * deferred: v1 is search + launch-app-only. A title-level [ProviderRef.deepLinkHint], once
 * reverse-engineered, will start being honored automatically.
 */
class ZattooProvider : StreamingProvider {
    override val id = ProviderId.ZATTOO
    override val displayName = "Zattoo"
    override val packageName = "com.zattoo.player"
    override val capabilities = ProviderCapabilities(
        canDeepLinkToTitle = false,
        isLiveTv = true,
        requiresRegion = true,
        requiresAuth = true,
    )

    override fun supportedRegions(): Set<Region> = setOf(Region.CH, Region.DE)

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? =
        ref.deepLinkHint?.let { Launcher.viewIntent(context, it, packageName) }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
