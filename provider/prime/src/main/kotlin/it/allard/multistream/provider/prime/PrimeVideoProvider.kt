package it.allard.multistream.provider.prime

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderSecrets
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.provider.api.DeepLinks
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.SessionState
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.provider.api.WebLoginSpec

/**
 * Prime Video. Best-effort web search via cookies captured by a WebView login. The bundled APK is
 * the living-room (TV) build; on phones the mobile build is tried. Titles are keyed by ASIN/gti.
 */
class PrimeVideoProvider(
    private val api: PrimeApi = PrimeApi(),
) : StreamingProvider {
    override val id = ProviderId.PRIME
    override val displayName = "Prime Video"
    override val packageName = "com.amazon.amazonvideo.livingroom"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canDeepLinkToTitle = true,
        requiresAuth = true,
    )

    @Volatile
    private var cookies: String? = null

    override fun webLoginSpec(): WebLoginSpec = WebLoginSpec(
        loginUrl = "https://www.primevideo.com",
        cookieUrl = "https://www.primevideo.com",
        successCookie = "at-main",
    )

    override suspend fun loginWithCookies(cookies: String): ProviderSecrets {
        this.cookies = cookies
        return ProviderSecrets(cookie = cookies)
    }

    override suspend fun ensureSession(config: ProviderConfig): SessionState {
        if (cookies == null) cookies = config.secrets.cookie
        return if (cookies != null) SessionState.Ready else SessionState.NeedsLogin("Prime Video login required")
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        val cookie = cookies ?: return emptyList()
        return try {
            api.search(query, cookie, region)
        } catch (e: PrimeApiException) {
            if (e.authError) cookies = null
            emptyList()
        }
    }

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: DeepLinks.primeDetail(ref.providerTitleId)
        return Launcher.viewIntent(context, url, packageName)
            ?: Launcher.viewIntent(context, url, MOBILE_PACKAGE)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName) ?: Launcher.launchApp(context, MOBILE_PACKAGE)

    private companion object {
        const val MOBILE_PACKAGE = "com.amazon.avod.thirdpartyclient"
    }
}
