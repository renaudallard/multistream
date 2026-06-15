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
 * Molotov (French live TV + replay) on the Fubo backend the current Molotov app uses. Search and
 * genre browse run against `api-eu.fubo.tv`, so every result carries its Fubo id; launch then builds
 * an `etincelle://{kind}/{id}` deep link into the etincelle app
 * (github.com/renaudallard/etincelle) when it is installed, and falls back to the official Molotov
 * app otherwise. Local tracking is provider-independent.
 */
class MolotovProvider(
    private val api: MolotovApi = MolotovApi(),
) : StreamingProvider {
    override val id = ProviderId.MOLOTOV
    override val displayName = "Molotov"
    override val packageName = "tv.molotov.app"

    // The official app is the install target (etincelle is sideload-only, not on the Play Store), so
    // it stays first; etincelle's two builds are listed so an etincelle-only install still counts as
    // installed instead of bouncing the user to the store.
    override val launchPackages = listOf(packageName, ETINCELLE, ETINCELLE_TV)

    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canBrowseByGenre = true,
        // Series catch-up episodes come from the show's "Regarder maintenant" tab on the Fubo backend.
        canListEpisodes = true,
        // etincelle deep-links a title by its Fubo id (etincelle://series|program|channel/{id}).
        canDeepLinkToTitle = true,
        isLiveTv = true,
        requiresRegion = true,
        requiresAuth = true,
    )

    override fun supportedRegions(): Set<Region> = setOf(Region.FR)

    override fun browsableGenres(): Set<Genre> = GENRE_KEYWORDS.keys

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var userId: String? = null

    @Volatile
    private var profileId: String? = null
    private val sessionMutex = Mutex()

    override suspend fun login(username: String, password: String): ProviderSecrets {
        val tokens = api.signin(username, password)
        val user = api.fetchUser(tokens.accessToken)
        // Under the mutex so this fresh login can't be clobbered by an in-flight background refresh.
        sessionMutex.withLock {
            accessToken = tokens.accessToken
            userId = user.userId
            profileId = user.profileId
        }
        // The password is never stored: only the tokens and the account/profile ids are persisted.
        return ProviderSecrets(
            token = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            extra = mapOf(EXTRA_USER_ID to user.userId, EXTRA_PROFILE_ID to user.profileId),
        )
    }

    override suspend fun ensureSession(config: ProviderConfig): SessionState {
        if (accessToken == null) {
            accessToken = config.secrets.token
            userId = config.secrets.extra[EXTRA_USER_ID]
            profileId = config.secrets.extra[EXTRA_PROFILE_ID]
        }
        // No stored password to fall back on, so a missing token means the user must re-login.
        return if (accessToken != null) SessionState.Ready else SessionState.NeedsLogin("Molotov login required")
    }

    override fun clearSession() {
        accessToken = null
        userId = null
        profileId = null
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        return withAuthRetry(config, emptyList()) { auth -> api.search(query, auth, region) }
    }

    // Fubo exposes no fine genre page, so (like Disney+) browse feeds a French genre keyword to search.
    override suspend fun browseByGenre(genre: Genre, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        val keyword = GENRE_KEYWORDS[genre] ?: return emptyList()
        return withAuthRetry(config, emptyList()) { auth -> api.search(keyword, auth, region) }
    }

    override suspend fun getSeasons(ref: ProviderRef, config: ProviderConfig): List<Season> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        // Only a series carries catch-up episodes; its ref is stamped "series:{id}".
        val parts = ref.providerTitleId.split(':', limit = 2)
        if (parts.size != 2 || parts[0] != "series") return emptyList()
        return try {
            withAuthRetry(config, emptyList()) { auth -> api.getSeasons(parts[1], auth) }
        } catch (e: MolotovApiException) {
            emptyList() // a series without catch-up exposes no episode tab
        }
    }

    /** Runs an authenticated Fubo call, refreshing the token once on an auth error and retrying. */
    private suspend fun <T> withAuthRetry(config: ProviderConfig, default: T, block: suspend (MolotovAuth) -> T): T {
        val auth = currentAuth() ?: return default
        return try {
            block(auth)
        } catch (e: MolotovApiException) {
            if (!e.authError) throw e
            val fresh = refreshSession(config, auth.accessToken) ?: return default
            runCatchingExceptCancellation { block(fresh) }.getOrDefault(default)
        }
    }

    private fun currentAuth(): MolotovAuth? = accessToken?.let { MolotovAuth(it, userId, profileId) }

    /** Refresh the session once, persisting the rotated token. Returns the new auth or null. */
    private suspend fun refreshSession(config: ProviderConfig, staleToken: String?): MolotovAuth? = sessionMutex.withLock {
        // Another caller may have refreshed while we waited for the lock.
        accessToken?.let { if (it != staleToken) return@withLock currentAuth() }
        val refreshToken = config.secrets.refreshToken ?: return@withLock null
        val tokens = runCatchingExceptCancellation { api.refresh(refreshToken) }.getOrElse { error ->
            // Only a genuine auth rejection means the refresh token is dead: clear the session so the
            // user re-logs in. A transient/network error keeps the token for a later retry, and
            // cancellation has already propagated.
            if (error is MolotovApiException && error.authError) {
                accessToken = null
                userId = null
                profileId = null
                config.persistSecrets?.invoke(ProviderSecrets.EMPTY)
            }
            return@withLock null
        }
        accessToken = tokens.accessToken
        persistRotated(tokens, config)
        currentAuth()
    }

    /** Persist a refreshed session so the rotated token (and the profile ids) survive a restart. */
    private fun persistRotated(tokens: MolotovTokens, config: ProviderConfig) {
        config.persistSecrets?.invoke(
            ProviderSecrets(
                token = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: config.secrets.refreshToken,
                extra = config.secrets.extra,
            ),
        )
    }

    // Launch into etincelle (the alternative Molotov client) when present: its mobile build accepts
    // the etincelle://{kind}/{id} deep link straight to the show's detail page. The official Molotov
    // app accepts no working content deep link, so it is only ever opened bare as a last resort.
    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? =
        ref.deepLinkHint?.let { Launcher.viewIntent(context, it, ETINCELLE) }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, ETINCELLE)
            ?: Launcher.launchApp(context, ETINCELLE_TV)
            ?: Launcher.launchApp(context, packageName)

    private companion object {
        const val ETINCELLE = "it.allard.etincelle"
        const val ETINCELLE_TV = "it.allard.etincelle.tv"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_PROFILE_ID = "profileId"

        // Canonical genre -> French catalog keyword fed to Fubo search (accent-free, as the catalog
        // tokenizes it), the same approach Disney+ uses for a backend with no genre-browse page.
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
