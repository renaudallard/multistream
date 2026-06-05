package it.allard.multistream.provider.zattoo

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
 * Zattoo (DACH live TV + replay). Search runs over the zapi program guide. Launch deep-links to the
 * program's live channel (zattoo.com/live/<cid>), which the app handles directly.
 */
class ZattooProvider(
    private val api: ZattooApi = ZattooApi(),
) : StreamingProvider {
    override val id = ProviderId.ZATTOO
    override val displayName = "Zattoo"
    override val packageName = "com.zattoo.player"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canDeepLinkToTitle = true,
        isLiveTv = true,
        requiresRegion = true,
        requiresAuth = true,
    )

    override fun supportedRegions(): Set<Region> = setOf(Region.CH, Region.DE)

    override suspend fun login(username: String, password: String): ProviderSecrets {
        api.login(username, password)
        return ProviderSecrets(extra = mapOf("email" to username, "password" to password))
    }

    override suspend fun ensureSession(config: ProviderConfig): SessionState {
        if (api.isLoggedIn()) return SessionState.Ready
        val email = config.secrets.extra["email"]
        val password = config.secrets.extra["password"]
        if (email != null && password != null) {
            return runCatching { api.login(email, password) }
                .fold({ SessionState.Ready }, { SessionState.NeedsLogin(it.message ?: "Login failed") })
        }
        return SessionState.NeedsLogin("Zattoo login required")
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        return runCatching { api.search(query, region) }.getOrDefault(emptyList())
    }

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? =
        ref.deepLinkHint?.let { Launcher.viewIntent(context, it, packageName) }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
