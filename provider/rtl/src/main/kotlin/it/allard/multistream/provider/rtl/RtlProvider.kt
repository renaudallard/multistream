package it.allard.multistream.provider.rtl

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.Genre
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.provider.api.runCatchingExceptCancellation
import java.net.URLEncoder

/**
 * RTL Play (Belgian RTL, DPG Media). Catalog search runs anonymously against DPG's lfvp platform
 * (`lfvp-api.dpgmedia.net/RTL_PLAY/search`, the same backend as VTM GO), which is geo-restricted to
 * Belgium. Launch deep-links to the title page (`rtlplay.be/rtlplay/<slug>~<detailId>`). There is no
 * login: the lfvp catalog is anonymous and the account SSO cookie is for a different host entirely.
 */
class RtlProvider(
    private val api: RtlApi = RtlApi(),
) : StreamingProvider {
    override val id = ProviderId.RTL
    override val displayName = "RTL Play"
    override val packageName = "com.tapptic.rtl.tvi"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canBrowseByGenre = true,
        canGetDetails = true,
        canListEpisodes = true,
        canDeepLinkToTitle = true,
        canInAppSearchDeepLink = true,
        requiresAuth = false,
    )

    override fun supportedRegions(): Set<Region> = setOf(Region("BE"))

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> =
        api.search(query, region)

    override fun browsableGenres(): Set<Genre> = GENRE_STOREFRONTS.keys

    // RTL Play groups its catalog by storefront (accueil, series, films, ...), which map to content type
    // rather than film genre; only documentaries correspond to a canonical genre.
    override suspend fun browseByGenre(genre: Genre, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        val storefront = GENRE_STOREFRONTS[genre] ?: return emptyList()
        return api.browseStorefront(storefront, region)
    }

    override suspend fun getDetails(ref: ProviderRef, config: ProviderConfig): ProviderTitleDetails? =
        runCatchingExceptCancellation { api.getDetails(ref.providerTitleId, ref) }.getOrNull()

    override suspend fun getSeasons(ref: ProviderRef, config: ProviderConfig): List<Season> =
        runCatchingExceptCancellation { api.getSeasons(ref.providerTitleId) }.getOrDefault(emptyList())

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: return Launcher.launchApp(context, packageName)
        return Launcher.viewIntent(context, url, packageName) ?: Launcher.launchApp(context, packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? {
        if (!query.isNullOrBlank()) {
            val url = "https://www.rtlplay.be/rtlplay/recherche?q=" + URLEncoder.encode(query, "UTF-8")
            Launcher.viewIntent(context, url, packageName)?.let { return it }
        }
        return Launcher.launchApp(context, packageName)
    }

    private companion object {
        // Canonical genre -> RTL Play storefront id. Only documentaries map to a canonical genre.
        val GENRE_STOREFRONTS = mapOf(
            Genre.DOCUMENTARY to "documentaires",
        )
    }
}
