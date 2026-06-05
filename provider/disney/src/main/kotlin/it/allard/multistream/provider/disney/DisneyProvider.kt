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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val sessionMutex = Mutex()

    override suspend fun getSeasons(ref: ProviderRef, config: ProviderConfig): List<Season> =
        authedCall(config, emptyList()) { token -> api.getSeasons(ref.providerTitleId, token) }

    override suspend fun getDetails(ref: ProviderRef, config: ProviderConfig): ProviderTitleDetails? =
        authedCall(config, null) { token -> api.getDetails(ref.providerTitleId, token, ref) }

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
        accessToken?.let { return SessionState.Ready }
        return sessionMutex.withLock {
            // Re-check inside the lock: a concurrent caller may have logged in while we waited, so we
            // do not start a second login.
            if (accessToken == null) accessToken = config.secrets.token
            accessToken?.let { return@withLock SessionState.Ready }
            val email = config.secrets.extra["email"]
            val password = config.secrets.extra["password"]
            if (email != null && password != null) {
                runCatching { login(email, password) }
                    .fold(
                        { fresh -> config.persistSecrets?.invoke(fresh); SessionState.Ready },
                        { SessionState.NeedsLogin(it.message ?: "Login failed") },
                    )
            } else {
                SessionState.NeedsLogin("Disney+ login required")
            }
        }
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> =
        authedCall(config, emptyList()) { token -> api.search(query, token, region) }

    /**
     * Run an authenticated API call, refreshing the session once if the access token has expired.
     * Non-auth failures degrade to [fallback] so one bad call never breaks the app.
     */
    private suspend fun <T> authedCall(config: ProviderConfig, fallback: T, call: suspend (String) -> T): T {
        if (ensureSession(config) !is SessionState.Ready) return fallback
        val token = accessToken ?: return fallback
        return runCatching { call(token) }.fold(
            { it },
            { error ->
                if (error is DisneyApiException && error.authError) {
                    refreshSession(config, token)?.let { fresh -> runCatching { call(fresh) }.getOrDefault(fallback) } ?: fallback
                } else {
                    fallback
                }
            },
        )
    }

    /** Refresh (or re-login) once, persisting the rotated session. Returns the new access token or null. */
    private suspend fun refreshSession(config: ProviderConfig, staleToken: String): String? = sessionMutex.withLock {
        // Another caller may have refreshed while we waited for the lock.
        accessToken?.let { if (it != staleToken) return@withLock it }
        val tokens = runCatching { config.secrets.refreshToken?.let { api.refresh(it) } }.getOrNull()
            ?: runCatching {
                val email = config.secrets.extra["email"]
                val password = config.secrets.extra["password"]
                if (email != null && password != null) api.login(email, password) else null
            }.getOrNull()
            ?: return@withLock null
        accessToken = tokens.accessToken
        persistRotated(tokens, config)
        tokens.accessToken
    }

    /** Persist a refreshed or re-logged-in session so the rotated refresh token survives a restart. */
    private fun persistRotated(tokens: DisneyTokens, config: ProviderConfig) {
        config.persistSecrets?.invoke(
            ProviderSecrets(
                token = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: config.secrets.refreshToken,
                extra = config.secrets.extra,
            ),
        )
    }

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: DeepLinks.disneyEntity(ref.providerTitleId)
        return Launcher.viewIntent(context, url, packageName)
            ?: Launcher.viewIntent(context, DeepLinks.disneyScheme(ref.providerTitleId), packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
