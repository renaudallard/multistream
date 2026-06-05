package it.allard.multistream.provider.rts

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
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
 * Play RTS (Swiss French public TV, SRG SSR). Search is anonymous via the Integration Layer and works
 * without login; an optional WebView login passes your profil.rts.ch session to the search
 * (best-effort). Launch opens the Play RTS app at the video page. Local tracking is provider-independent.
 */
class RtsProvider(
    private val api: RtsApi = RtsApi(),
) : StreamingProvider {
    override val id = ProviderId.RTS
    override val displayName = "Play RTS"
    override val packageName = "ch.rts.player"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canDeepLinkToTitle = true,
        requiresAuth = false,
        optionalLogin = true,
    )

    override fun supportedRegions(): Set<Region> = setOf(Region("CH"))

    override fun webLoginSpec(): WebLoginSpec = WebLoginSpec(
        loginUrl = "https://www.rts.ch/profile/login",
        cookieUrl = "https://www.rts.ch",
        successCookie = "",
        autoCapture = false,
    )

    override suspend fun loginWithCookies(cookies: String): ProviderSecrets =
        ProviderSecrets(cookie = cookies)

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> =
        runCatching { api.search(query, config.secrets.cookie) }.getOrDefault(emptyList())

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: return Launcher.launchApp(context, packageName)
        return Launcher.viewIntent(context, url, packageName) ?: Launcher.launchApp(context, packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
