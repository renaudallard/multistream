package it.allard.multistream.provider.disney

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.Genre
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
import it.allard.multistream.provider.api.runCatchingExceptCancellation
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
        canBrowseByGenre = true,
        canFetchWatchState = true,
        canDeepLinkToTitle = true,
        requiresAuth = true,
    )

    @Volatile
    private var accessToken: String? = null
    private val sessionMutex = Mutex()

    override fun browsableGenres(): Set<Genre> = GENRE_KEYWORDS.keys

    // Disney+ exposes no browseable genre-set page (the explore home page is unsupported), but its search
    // is genre-aware: querying a genre name returns that genre's titles. Browse maps each canonical genre
    // to the matching French catalog keyword and reuses the search path and parser.
    override suspend fun browseByGenre(genre: Genre, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        val keyword = GENRE_KEYWORDS[genre] ?: return emptyList()
        return authedCall(config, emptyList()) { token -> api.search(keyword, token, region) }
    }

    override suspend fun getSeasons(ref: ProviderRef, config: ProviderConfig): List<Season> =
        authedCall(config, emptyList()) { token -> api.getSeasons(ref.providerTitleId, token) }

    override suspend fun getDetails(ref: ProviderRef, config: ProviderConfig): ProviderTitleDetails? =
        authedCall(config, null) { token -> api.getDetails(ref.providerTitleId, token, ref) }

    override suspend fun fetchWatchedEpisodes(ref: ProviderRef, config: ProviderConfig): List<EpisodeCoord> =
        authedCall(config, emptyList()) { token -> api.fetchWatchedEpisodes(ref.providerTitleId, token) }

    override suspend fun login(username: String, password: String): ProviderSecrets {
        val tokens = api.login(username, password)
        accessToken = tokens.accessToken
        // The password is never stored: only the access and refresh tokens are persisted.
        return ProviderSecrets(token = tokens.accessToken, refreshToken = tokens.refreshToken)
    }

    override suspend fun ensureSession(config: ProviderConfig): SessionState {
        accessToken?.let { return SessionState.Ready }
        return sessionMutex.withLock {
            // Re-check inside the lock: a concurrent caller may have established the session while we
            // waited. There is no stored password to fall back on, so a missing token means re-login.
            if (accessToken == null) accessToken = config.secrets.token
            if (accessToken != null) SessionState.Ready else SessionState.NeedsLogin("Disney+ login required")
        }
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> =
        authedCall(config, emptyList()) { token -> api.search(query, token, region) }

    /**
     * Run an authenticated API call, refreshing the session once if the access token has expired.
     * [fallback] covers the not-logged-in cases; real API failures propagate so the caller can
     * report the provider as unavailable instead of pretending it found nothing.
     */
    private suspend fun <T> authedCall(config: ProviderConfig, fallback: T, call: suspend (String) -> T): T {
        if (ensureSession(config) !is SessionState.Ready) return fallback
        val token = accessToken ?: return fallback
        val result = runCatchingExceptCancellation { call(token) }
        result.onSuccess { return it }
        val error = result.exceptionOrNull()
        if (error is DisneyApiException && error.authError) {
            val fresh = refreshSession(config, token) ?: return fallback
            return call(fresh)
        }
        throw error ?: IllegalStateException("authedCall failed without an exception")
    }

    /** Refresh the session once, persisting the rotated token. Returns the new access token or null. */
    private suspend fun refreshSession(config: ProviderConfig, staleToken: String): String? = sessionMutex.withLock {
        // Another caller may have refreshed while we waited for the lock.
        accessToken?.let { if (it != staleToken) return@withLock it }
        val refreshToken = config.secrets.refreshToken ?: return@withLock null
        val tokens = runCatchingExceptCancellation { api.refresh(refreshToken) }.getOrElse { error ->
            // Only a genuine auth rejection means the refresh token is dead: clear the session so the
            // user re-logs in. A transient/network error keeps the token for a later retry, and
            // cancellation has already propagated.
            if (error is DisneyApiException && error.authError) {
                accessToken = null
                config.persistSecrets?.invoke(ProviderSecrets.EMPTY)
            }
            return@withLock null
        }
        accessToken = tokens.accessToken
        persistRotated(tokens, config)
        tokens.accessToken
    }

    /** Persist a refreshed session so the rotated refresh token survives a restart. */
    private fun persistRotated(tokens: DisneyTokens, config: ProviderConfig) {
        config.persistSecrets?.invoke(
            ProviderSecrets(
                token = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: config.secrets.refreshToken,
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

    private companion object {
        // Canonical genre -> French catalog keyword fed to Disney+ search (accent-free, as the catalog
        // tokenizes it). Kept to the genres Disney+ actually carries.
        val GENRE_KEYWORDS = mapOf(
            Genre.COMEDY to "comedie",
            Genre.DRAMA to "drame",
            Genre.HORROR to "horreur",
            Genre.ACTION to "action",
            Genre.DOCUMENTARY to "documentaire",
            Genre.SCIFI to "science-fiction",
            Genre.CRIME to "policier",
            Genre.ROMANCE to "romance",
            Genre.ANIMATION to "animation",
            Genre.KIDS to "jeunesse",
        )
    }
}
