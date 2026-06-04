package it.allard.multistream.provider.rtbf

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
 * RTBF Auvio (free Belgian French public TV). Search is anonymous and works without login; the
 * optional WebView login passes your auvio.rtbf.be session to the search (best-effort, to surface
 * account-bound or premium listings). Launch opens the Auvio app at the auvio.rtbf.be URL. Local
 * tracking is provider-independent.
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
        optionalLogin = true,
    )

    @Volatile
    private var cookie: String? = null

    override fun supportedRegions(): Set<Region> = setOf(Region("BE"))

    override fun webLoginSpec(): WebLoginSpec = WebLoginSpec(
        loginUrl = "https://auvio.rtbf.be/connexion",
        cookieUrl = "https://auvio.rtbf.be",
        successCookie = "",
        autoCapture = false,
    )

    override suspend fun loginWithCookies(cookies: String): ProviderSecrets {
        cookie = cookies
        return ProviderSecrets(cookie = cookies)
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        if (cookie == null) cookie = config.secrets.cookie
        return runCatching { api.search(query, cookie) }.getOrDefault(emptyList())
    }

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: return Launcher.launchApp(context, packageName)
        return Launcher.viewIntent(context, url, packageName) ?: Launcher.launchApp(context, packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
