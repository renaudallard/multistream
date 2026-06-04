package it.allard.multistream.provider.rtbf

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.StreamingProvider

/**
 * RTBF Auvio (free Belgian French public TV). Search is anonymous; launch opens the Auvio app at the
 * auvio.rtbf.be URL. Local tracking is provider-independent.
 */
class RtbfProvider(
    private val api: RtbfApi = RtbfApi(),
) : StreamingProvider {
    override val id = ProviderId.RTBF
    override val displayName = "RTBF Auvio"
    override val packageName = "be.rtbf.auvio"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canDeepLinkToTitle = true,
        requiresAuth = false,
    )

    override fun supportedRegions(): Set<Region> = setOf(Region("BE"))

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> =
        runCatching { api.search(query) }.getOrDefault(emptyList())

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: return Launcher.launchApp(context, packageName)
        return Launcher.viewIntent(context, url, packageName) ?: Launcher.launchApp(context, packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
