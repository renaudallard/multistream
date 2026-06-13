package it.allard.multistream.provider.api

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

/**
 * One streaming service plugin. The deep-link launch is the always-on capability; search and
 * detail are optional and gated behind [capabilities]. Each provider is a self-contained leaf
 * module that knows nothing about the others or the UI.
 */
interface StreamingProvider {
    val id: ProviderId
    val displayName: String
    val packageName: String
    val capabilities: ProviderCapabilities

    /**
     * Every package this provider may be installed as, most-preferred first (e.g. Prime ships a phone
     * build and a separate TV build). Used to detect installation and pick the Play Store target.
     */
    val launchPackages: List<String> get() = listOf(packageName)

    fun supportedRegions(): Set<Region> = emptySet()

    /** Establish/refresh a session. No-op for open providers. */
    suspend fun ensureSession(config: ProviderConfig): SessionState = SessionState.Anonymous

    /**
     * Drop any in-memory session (cached cookies/tokens). Called on logout so the change takes effect
     * without restarting the app — providers are process-lifetime singletons, so a cached session
     * would otherwise outlive the cleared secret. No-op for stateless providers.
     */
    fun clearSession() {}

    /**
     * Authenticate with new credentials and return secrets to persist (encrypted), or null if the
     * provider needs no login. Called by the Settings login form.
     */
    suspend fun login(username: String, password: String): ProviderSecrets? = null

    /** If non-null, this provider logs in via a WebView that captures cookies (no password form). */
    fun webLoginSpec(): WebLoginSpec? = null

    /** Complete a WebView login with the captured cookie header; returns secrets to persist. */
    suspend fun loginWithCookies(cookies: String): ProviderSecrets? = null

    /**
     * Begin a device-link login (the provider shows a short code the user enters on a web page,
     * e.g. plex.tv/link). Returns null if unsupported. The returned session carries the code to
     * display and a suspending poll that resolves to secrets once the user has linked, or null on
     * timeout. Used instead of a password form when [ProviderCapabilities.linkLogin] is set.
     */
    suspend fun beginLink(): LinkSession? = null

    /** Catalog search. Only called when [ProviderCapabilities.canSearch] is true. */
    suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> = emptyList()

    /** The canonical genres this provider can browse. Empty unless [ProviderCapabilities.canBrowseByGenre]. */
    fun browsableGenres(): Set<Genre> = emptySet()

    /**
     * Titles for a genre, without a text query. Only called when [ProviderCapabilities.canBrowseByGenre]
     * and the genre is in [browsableGenres]; returns empty for a genre the provider does not carry.
     */
    suspend fun browseByGenre(genre: Genre, region: Region, config: ProviderConfig): List<UnifiedSearchResult> = emptyList()

    /** Detail for one of this provider's refs. Only called when [ProviderCapabilities.canGetDetails]. */
    suspend fun getDetails(ref: ProviderRef, config: ProviderConfig): ProviderTitleDetails? = null

    /** Season/episode enumeration. Only called when [ProviderCapabilities.canListEpisodes]. */
    suspend fun getSeasons(ref: ProviderRef, config: ProviderConfig): List<Season> = emptyList()

    /**
     * The episodes the member has already watched on the provider's own service, for importing into
     * local tracking. Only called when [ProviderCapabilities.canFetchWatchState].
     */
    suspend fun fetchWatchedEpisodes(ref: ProviderRef, config: ProviderConfig): List<EpisodeCoord> = emptyList()

    /**
     * Build an Intent that opens [ref] (optionally a specific [episode]) in the provider's app,
     * or null if no title-level deep link can be built. Providers downgrade to the title page when
     * episode deep-linking is unsupported.
     */
    fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord? = null): Intent?

    /** Fallback: open the app, optionally pre-loading a search query inside it. */
    fun launchAppFallback(context: Context, query: String? = null): Intent?
}
