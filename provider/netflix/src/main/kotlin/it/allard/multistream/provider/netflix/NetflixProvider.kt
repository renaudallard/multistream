package it.allard.multistream.provider.netflix

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderSecrets
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.provider.api.DeepLinks
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.SessionState
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.provider.api.WebLoginSpec

/**
 * Netflix. The app API is MSL-locked, so search uses the web Shakti API with cookies captured by a
 * WebView login. Title deep links and in-app search deep links work regardless.
 */
class NetflixProvider(
    private val api: NetflixApi = NetflixApi(),
) : StreamingProvider {
    override val id = ProviderId.NETFLIX
    override val displayName = "Netflix"
    override val packageName = "com.netflix.mediaclient"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canGetDetails = true,
        canListEpisodes = true,
        canFetchWatchState = true,
        canDeepLinkToTitle = true,
        canInAppSearchDeepLink = true,
        requiresAuth = true,
    )

    @Volatile
    private var cookies: String? = null

    override suspend fun getSeasons(ref: ProviderRef, config: ProviderConfig): List<Season> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        val cookie = cookies ?: return emptyList()
        return runCatching { api.getSeasons(ref.providerTitleId, cookie) }.getOrDefault(emptyList())
    }

    override suspend fun getDetails(ref: ProviderRef, config: ProviderConfig): ProviderTitleDetails? {
        if (ensureSession(config) !is SessionState.Ready) return null
        val cookie = cookies ?: return null
        return try {
            val details = api.getDetails(ref.providerTitleId, cookie, ref)
            persistRotated(cookie, config)
            details
        } catch (e: NetflixApiException) {
            if (e.authError) api.invalidate()
            null
        }
    }

    override suspend fun fetchWatchedEpisodes(ref: ProviderRef, config: ProviderConfig): List<EpisodeCoord> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        val cookie = cookies ?: return emptyList()
        return try {
            val watched = api.fetchWatchedEpisodes(ref.providerTitleId, cookie)
            persistRotated(cookie, config)
            watched
        } catch (e: NetflixApiException) {
            if (e.authError) api.invalidate()
            emptyList()
        }
    }

    override fun webLoginSpec(): WebLoginSpec = WebLoginSpec(
        loginUrl = "https://www.netflix.com/login",
        cookieUrl = "https://www.netflix.com",
        successCookie = "NetflixId",
        // Sign out server-side first; otherwise Netflix re-auths a stale session the API rejects.
        logoutUrl = "https://www.netflix.com/SignOut",
        // NetflixId is set on page load (before login), so don't auto-capture: let the user sign in
        // for real, then confirm with the button to capture an authenticated session.
        autoCapture = false,
    )

    override suspend fun loginWithCookies(cookies: String): ProviderSecrets {
        this.cookies = cookies
        api.reset()
        return ProviderSecrets(cookie = cookies)
    }

    override suspend fun ensureSession(config: ProviderConfig): SessionState {
        if (cookies == null) cookies = config.secrets.cookie
        return if (cookies != null) SessionState.Ready else SessionState.NeedsLogin("Netflix login required")
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        val cookie = cookies ?: return emptyList()
        return try {
            val results = api.search(query, cookie, region)
            persistRotated(cookie, config)
            results
        } catch (e: NetflixApiException) {
            if (e.authError) api.invalidate()
            emptyList()
        }
    }

    /** If Netflix rotated the session cookies during the call, keep and persist them. */
    private fun persistRotated(seeded: String, config: ProviderConfig) {
        val current = api.currentCookies()
        if (current.contains("NetflixId") && current != seeded) {
            cookies = current
            config.persistSecrets?.invoke(ProviderSecrets(cookie = current))
        }
    }

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val titleId = ref.providerTitleId
        return Launcher.viewIntent(context, DeepLinks.netflixTitle(titleId), packageName)
            ?: Launcher.viewIntent(context, DeepLinks.netflixTitleScheme(titleId), packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? {
        if (!query.isNullOrBlank()) {
            Launcher.viewIntent(context, DeepLinks.netflixSearch(query), packageName)?.let { return it }
        }
        return Launcher.launchApp(context, packageName)
    }
}
