package it.allard.multistream.provider.molotov

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.Genre
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderSecrets
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.SessionState
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.provider.api.runCatchingExceptCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Molotov (French live TV + replay). Search runs against the molotov front API; launch opens the
 * app via a molotov.tv app link. Local tracking is provider-independent.
 */
class MolotovProvider(
    private val api: MolotovApi = MolotovApi(),
) : StreamingProvider {
    override val id = ProviderId.MOLOTOV
    override val displayName = "Molotov"
    override val packageName = "tv.molotov.app"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canBrowseByGenre = true,
        canListEpisodes = true,
        // No deep link: the Fubo-based app accepts no external link to a title, so launch opens the
        // app. Keeping this false stops Settings advertising a "Deep link" capability it lacks.
        canDeepLinkToTitle = false,
        isLiveTv = true,
        requiresRegion = true,
        requiresAuth = true,
    )

    override fun supportedRegions(): Set<Region> = setOf(Region.FR)

    override fun browsableGenres(): Set<Genre> = GENRE_KINDS.keys

    override suspend fun browseByGenre(genre: Genre, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        val kind = GENRE_KINDS[genre] ?: return emptyList()
        val token = accessToken ?: return emptyList()
        return try {
            api.browseByKind(kind, token, region)
        } catch (e: MolotovApiException) {
            if (e.authError) retryBrowseAfterAuth(kind, region, config) else throw e
        }
    }

    private suspend fun retryBrowseAfterAuth(kind: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        val fresh = refreshSession(config, accessToken) ?: return emptyList()
        return runCatchingExceptCancellation { api.browseByKind(kind, fresh, region) }.getOrDefault(emptyList())
    }

    @Volatile
    private var accessToken: String? = null
    private val sessionMutex = Mutex()

    override suspend fun login(username: String, password: String): ProviderSecrets {
        val tokens = api.login(username, password)
        // Under the mutex so this fresh login can't be clobbered by an in-flight background refresh
        // (which holds the lock for its whole duration), and vice versa.
        sessionMutex.withLock { accessToken = tokens.accessToken }
        // The password is never stored: only the access and refresh tokens are persisted.
        return ProviderSecrets(token = tokens.accessToken, refreshToken = tokens.refreshToken)
    }

    override suspend fun ensureSession(config: ProviderConfig): SessionState {
        if (accessToken == null) accessToken = config.secrets.token
        // No stored password to fall back on, so a missing token means the user must re-login.
        return if (accessToken != null) SessionState.Ready else SessionState.NeedsLogin("Molotov login required")
    }

    override fun clearSession() {
        accessToken = null
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        val token = accessToken ?: return emptyList()
        return try {
            api.search(query, token, region)
        } catch (e: MolotovApiException) {
            if (e.authError) retryAfterAuth(query, region, config) else throw e
        }
    }

    private suspend fun retryAfterAuth(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        val fresh = refreshSession(config, accessToken) ?: return emptyList()
        return runCatchingExceptCancellation { api.search(query, fresh, region) }.getOrDefault(emptyList())
    }

    override suspend fun getSeasons(ref: ProviderRef, config: ProviderConfig): List<Season> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        val token = accessToken ?: return emptyList()
        // The episode endpoint is channel-scoped; the parser stamps refs with "channel:program" when
        // the tile metadata carries both ids. A slug-only ref (older cache, or a tile without channel
        // metadata) cannot list episodes.
        val parts = ref.providerTitleId.split(':')
        if (parts.size != 2) return emptyList()
        val (channelId, programId) = parts
        return try {
            api.getSeasons(channelId, programId, token)
        } catch (e: MolotovApiException) {
            if (!e.authError) throw e
            val fresh = refreshSession(config, token) ?: return emptyList()
            runCatchingExceptCancellation { api.getSeasons(channelId, programId, fresh) }.getOrDefault(emptyList())
        }
    }

    /** Refresh the session once, persisting the rotated token. Returns the new access token or null. */
    private suspend fun refreshSession(config: ProviderConfig, staleToken: String?): String? = sessionMutex.withLock {
        // Another caller may have refreshed while we waited for the lock.
        accessToken?.let { if (it != staleToken) return@withLock it }
        val refreshToken = config.secrets.refreshToken ?: return@withLock null
        val tokens = runCatchingExceptCancellation { api.refresh(refreshToken) }.getOrElse { error ->
            // Only a genuine auth rejection means the refresh token is dead: clear the session so the
            // user re-logs in. A transient/network error keeps the token for a later retry, and
            // cancellation has already propagated.
            if (error is MolotovApiException && error.authError) {
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
    private fun persistRotated(tokens: MolotovTokens, config: ProviderConfig) {
        config.persistSecrets?.invoke(
            ProviderSecrets(
                token = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: config.secrets.refreshToken,
            ),
        )
    }

    // The Fubo-based Molotov app exposes no external deep link to a program: its handler posts the
    // URL to a server resolver that answers "no url found" for every content URL, so any deep link
    // would just bounce to the home screen anyway. Open the app directly instead.
    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? =
        Launcher.launchApp(context, packageName)

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)

    private companion object {
        // Canonical genre -> Molotov "kind" section of the Films category (id 1). Molotov has no
        // documentary film kind, so DOCUMENTARY is omitted.
        val GENRE_KINDS = mapOf(
            Genre.COMEDY to "kind_movies_1",
            Genre.DRAMA to "kind_movies_23",
            Genre.HORROR to "kind_movies_29",
            Genre.ACTION to "kind_movies_27",
            Genre.SCIFI to "kind_movies_31",
            Genre.CRIME to "kind_movies_16",
            Genre.ROMANCE to "kind_movies_116",
            Genre.ANIMATION to "kind_movies_28",
            Genre.KIDS to "kind_movies_142",
        )
    }
}
