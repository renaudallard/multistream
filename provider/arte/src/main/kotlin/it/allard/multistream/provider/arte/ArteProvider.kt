package it.allard.multistream.provider.arte

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
 * Arte (free French/German public TV). Search is anonymous — login is optional and not needed for
 * search/launch/tracking. The per-provider region selects the catalog language. Launch opens the
 * Arte app at the arte.tv URL (falling back to the arte:// scheme).
 */
class ArteProvider(
    private val api: ArteApi = ArteApi(),
) : StreamingProvider {
    override val id = ProviderId.ARTE
    override val displayName = "Arte"
    override val packageName = "tv.arte.plus7"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canDeepLinkToTitle = true,
        requiresRegion = true,
        requiresAuth = false,
    )

    override fun supportedRegions(): Set<Region> =
        setOf(Region("FR"), Region("DE"), Region("EN"), Region("ES"), Region("IT"), Region("PL"))

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> =
        runCatching { api.search(query, langFor(region)) }.getOrDefault(emptyList())

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint
            ?: "https://www.arte.tv/${langFor(ref.region ?: Region.FR)}/videos/${ref.providerTitleId}/"
        return Launcher.viewIntent(context, url, packageName)
            ?: Launcher.viewIntent(context, "arte://collection/${ref.providerTitleId}", packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)

    private fun langFor(region: Region): String = when (region.code.uppercase()) {
        "DE" -> "de"
        "EN", "GB", "US" -> "en"
        "ES" -> "es"
        "IT" -> "it"
        "PL" -> "pl"
        else -> "fr"
    }
}
