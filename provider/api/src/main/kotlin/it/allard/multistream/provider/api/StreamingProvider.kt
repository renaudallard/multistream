package it.allard.multistream.provider.api

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

    fun supportedRegions(): Set<Region> = emptySet()

    /** Establish/refresh a session. No-op for open providers. */
    suspend fun ensureSession(config: ProviderConfig): SessionState = SessionState.Anonymous

    /**
     * Authenticate with new credentials and return secrets to persist (encrypted), or null if the
     * provider needs no login. Called by the Settings login form.
     */
    suspend fun login(username: String, password: String): ProviderSecrets? = null

    /** Catalog search. Only called when [ProviderCapabilities.canSearch] is true. */
    suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> = emptyList()

    /** Detail for one of this provider's refs. Only called when [ProviderCapabilities.canGetDetails]. */
    suspend fun getDetails(ref: ProviderRef, config: ProviderConfig): ProviderTitleDetails? = null

    /** Season/episode enumeration. Only called when [ProviderCapabilities.canListEpisodes]. */
    suspend fun getSeasons(ref: ProviderRef, config: ProviderConfig): List<Season> = emptyList()

    /**
     * Build an Intent that opens [ref] (optionally a specific [episode]) in the provider's app,
     * or null if no title-level deep link can be built. Providers downgrade to the title page when
     * episode deep-linking is unsupported.
     */
    fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord? = null): Intent?

    /** Fallback: open the app, optionally pre-loading a search query inside it. */
    fun launchAppFallback(context: Context, query: String? = null): Intent?
}
