package it.allard.multistream.provider.disney

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

/**
 * Disney+. Search runs against the bamgrid explore API; launch opens the app via an auto-verified
 * disneyplus.com entity link. Profiles with a PIN are skipped during login.
 */
class DisneyProvider(
    private val api: DisneyApi = DisneyApi(),
) : StreamingProvider {
    override val id = ProviderId.DISNEY
    override val displayName = "Disney+"
    override val packageName = "com.disney.disneyplus"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canGetDetails = true,
        canListEpisodes = true,
        canDeepLinkToTitle = true,
        requiresAuth = true,
    )

    @Volatile
    private var accessToken: String? = null

    override suspend fun getSeasons(ref: ProviderRef, config: ProviderConfig): List<Season> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        val token = accessToken ?: return emptyList()
        return runCatching { api.getSeasons(ref.providerTitleId, token) }.getOrDefault(emptyList())
    }

    override suspend fun getDetails(ref: ProviderRef, config: ProviderConfig): ProviderTitleDetails? {
        if (ensureSession(config) !is SessionState.Ready) return null
        val token = accessToken ?: return null
        return runCatching { api.getDetails(ref.providerTitleId, token, ref) }.getOrNull()
    }

    override suspend fun login(username: String, password: String): ProviderSecrets {
        val tokens = api.login(username, password)
        accessToken = tokens.accessToken
        return ProviderSecrets(
            token = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            extra = mapOf("email" to username, "password" to password),
        )
    }

    override suspend fun ensureSession(config: ProviderConfig): SessionState {
        if (accessToken == null) accessToken = config.secrets.token
        if (accessToken != null) return SessionState.Ready

        val email = config.secrets.extra["email"]
        val password = config.secrets.extra["password"]
        if (email != null && password != null) {
            return runCatching { login(email, password) }
                .fold({ SessionState.Ready }, { SessionState.NeedsLogin(it.message ?: "Login failed") })
        }
        return SessionState.NeedsLogin("Disney+ login required")
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        val token = accessToken ?: return emptyList()
        return try {
            api.search(query, token, region)
        } catch (e: DisneyApiException) {
            if (e.authError) retryAfterAuth(query, region, config) else emptyList()
        }
    }

    private suspend fun retryAfterAuth(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        val refreshed = runCatching { config.secrets.refreshToken?.let { api.refresh(it).accessToken } }.getOrNull()
            ?: runCatching {
                val email = config.secrets.extra["email"]
                val password = config.secrets.extra["password"]
                if (email != null && password != null) api.login(email, password).accessToken else null
            }.getOrNull()
            ?: return emptyList()
        accessToken = refreshed
        return runCatching { api.search(query, refreshed, region) }.getOrDefault(emptyList())
    }

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: DeepLinks.disneyEntity(ref.providerTitleId)
        return Launcher.viewIntent(context, url, packageName)
            ?: Launcher.viewIntent(context, DeepLinks.disneyScheme(ref.providerTitleId), packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
