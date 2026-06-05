package it.allard.multistream.provider.rtl

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
import java.net.URLEncoder

/**
 * RTL Play (Belgian RTL, DPG Media). Catalog search runs anonymously against DPG's lfvp platform
 * (`lfvp-api.dpgmedia.net/RTL_PLAY/search`, the same backend as VTM GO), which is geo-restricted to
 * Belgium. Launch deep-links to the title page (`rtlplay.be/rtlplay/<slug>~<detailId>`). The optional
 * login points at RTL's account SSO (sso.rtl.be); it is not required for search.
 */
class RtlProvider(
    private val api: RtlApi = RtlApi(),
) : StreamingProvider {
    override val id = ProviderId.RTL
    override val displayName = "RTL Play"
    override val packageName = "com.tapptic.rtl.tvi"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canDeepLinkToTitle = true,
        canInAppSearchDeepLink = true,
        requiresAuth = false,
        optionalLogin = true,
    )

    override fun supportedRegions(): Set<Region> = setOf(Region("BE"))

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> =
        runCatching { api.search(query, region) }.getOrDefault(emptyList())

    override fun webLoginSpec(): WebLoginSpec = WebLoginSpec(
        loginUrl = "https://sso.rtl.be/",
        cookieUrl = "https://sso.rtl.be",
        successCookie = "",
        autoCapture = false,
    )

    override suspend fun loginWithCookies(cookies: String): ProviderSecrets = ProviderSecrets(cookie = cookies)

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
}
