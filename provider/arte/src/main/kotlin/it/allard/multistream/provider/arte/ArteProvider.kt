package it.allard.multistream.provider.arte

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.Genre
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderSecrets
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.provider.api.WebLoginSpec

/**
 * Arte (free French/German public TV). Search is anonymous and works without login; the optional
 * WebView login passes your arte.tv session to the search (best-effort — Arte's catalog is free, so
 * it mainly affects account-bound listings). The per-provider region selects the catalog language.
 * Launch opens the Arte app at the arte.tv URL (falling back to the arte:// scheme).
 */
class ArteProvider(
    private val api: ArteApi = ArteApi(),
) : StreamingProvider {
    override val id = ProviderId.ARTE
    override val displayName = "Arte"
    override val packageName = "tv.arte.plus7"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canBrowseByGenre = true,
        canDeepLinkToTitle = true,
        requiresRegion = true,
        requiresAuth = false,
        optionalLogin = true,
    )

    override fun supportedRegions(): Set<Region> =
        setOf(Region("FR"), Region("DE"), Region("EN"), Region("ES"), Region("IT"), Region("PL"))

    override fun browsableGenres(): Set<Genre> = GENRE_CODES.keys

    // Arte's catalog is thematic (Cinema, Series, Documentaires, ...) rather than by film genre, so only
    // the categories that map cleanly to a canonical genre are exposed. FAM ("a voir en famille") is the
    // closest fit for KIDS (family viewing rather than a pure children's catalog).
    override suspend fun browseByGenre(genre: Genre, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        val code = GENRE_CODES[genre] ?: return emptyList()
        return api.browseGenre(code, langFor(region), config.secrets.cookie)
    }

    override fun webLoginSpec(): WebLoginSpec = WebLoginSpec(
        loginUrl = "https://www.arte.tv/fr/profile/auth/login/",
        cookieUrl = "https://www.arte.tv",
        successCookie = "",
        autoCapture = false,
    )

    override suspend fun loginWithCookies(cookies: String): ProviderSecrets =
        ProviderSecrets(cookie = cookies)

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> =
        api.search(query, langFor(region), config.secrets.cookie)

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

    private companion object {
        // Canonical genre -> Arte EMAC page code. Arte has no dedicated comedy/horror/action/etc. page,
        // so only documentaries and family viewing are offered.
        val GENRE_CODES = mapOf(
            Genre.DOCUMENTARY to "DOR",
            Genre.KIDS to "FAM",
        )
    }
}
