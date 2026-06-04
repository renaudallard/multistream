package it.allard.multistream.provider.plex

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
import it.allard.multistream.provider.api.SessionState
import it.allard.multistream.provider.api.StreamingProvider

/**
 * Plex. Email/password login yields an X-Plex-Token; search runs against Plex Discover and is keyed
 * by ratingKey, deep-linking into the Plex app at watch.plex.tv. Region-independent.
 */
class PlexProvider(
    private val api: PlexApi = PlexApi(),
) : StreamingProvider {
    override val id = ProviderId.PLEX
    override val displayName = "Plex"
    override val packageName = "com.plexapp.android"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canDeepLinkToTitle = true,
        requiresAuth = true,
    )

    @Volatile
    private var token: String? = null

    override suspend fun login(username: String, password: String): ProviderSecrets {
        val t = api.login(username, password)
        token = t
        return ProviderSecrets(token = t)
    }

    override suspend fun ensureSession(config: ProviderConfig): SessionState {
        if (token == null) token = config.secrets.token
        return if (token != null) SessionState.Ready else SessionState.NeedsLogin("Plex login required")
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        return try {
            api.search(query, token)
        } catch (e: PlexApiException) {
            if (e.authError) token = null
            emptyList()
        }
    }

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: return Launcher.launchApp(context, packageName)
        return Launcher.viewIntent(context, url, packageName) ?: Launcher.launchApp(context, packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
