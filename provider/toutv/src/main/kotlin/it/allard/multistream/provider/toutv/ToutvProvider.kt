package it.allard.multistream.provider.toutv

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.provider.api.runCatchingExceptCancellation

/**
 * ICI Tou.tv, Radio-Canada's French streaming service (Quebec). Search and detail use the public
 * Radio-Canada OTT catalog API, which is anonymous and answers worldwide; playback stays in the
 * official app and is geo-locked to Canada. Launch opens the title page on ici.tou.tv in the Tou.tv
 * app. Region-independent (a single Canadian catalog).
 */
class ToutvProvider(
    private val api: ToutvApi = ToutvApi(),
) : StreamingProvider {
    override val id = ProviderId.TOUTV
    override val displayName = "ICI Tou.tv"
    override val packageName = "tv.tou.android"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canGetDetails = true,
        canDeepLinkToTitle = true,
        requiresAuth = false,
    )

    override fun supportedRegions(): Set<Region> = setOf(Region("CA"))

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> =
        runCatchingExceptCancellation { api.search(query) }.getOrDefault(emptyList())

    override suspend fun getDetails(ref: ProviderRef, config: ProviderConfig): ProviderTitleDetails? =
        runCatchingExceptCancellation { api.getDetails(ref.providerTitleId, ref) }.getOrNull()

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: "https://ici.tou.tv/${ref.providerTitleId}"
        return Launcher.viewIntent(context, url, packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
