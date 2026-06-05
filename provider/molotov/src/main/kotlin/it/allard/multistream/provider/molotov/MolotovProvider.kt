package it.allard.multistream.provider.molotov

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
        canDeepLinkToTitle = true,
        isLiveTv = true,
        requiresRegion = true,
        requiresAuth = true,
    )

    override fun supportedRegions(): Set<Region> = setOf(Region.FR)

    @Volatile
    private var accessToken: String? = null

    override suspend fun login(username: String, password: String): ProviderSecrets {
        val tokens = api.login(username, password)
        accessToken = tokens.accessToken
        // The password is never stored: only the access and refresh tokens are persisted.
        return ProviderSecrets(token = tokens.accessToken, refreshToken = tokens.refreshToken)
    }

    override suspend fun ensureSession(config: ProviderConfig): SessionState {
        if (accessToken == null) accessToken = config.secrets.token
        // No stored password to fall back on, so a missing token means the user must re-login.
        return if (accessToken != null) SessionState.Ready else SessionState.NeedsLogin("Molotov login required")
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        val token = accessToken ?: return emptyList()
        return try {
            api.search(query, token, region)
        } catch (e: MolotovApiException) {
            if (e.authError) retryAfterAuth(query, region, config) else emptyList()
        }
    }

    private suspend fun retryAfterAuth(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        val tokens = runCatching { config.secrets.refreshToken?.let { api.refresh(it) } }.getOrNull()
        if (tokens == null) {
            // No password is stored, so we cannot silently re-login: clear the dead session so the
            // user shows as logged out and can sign in again from Settings.
            accessToken = null
            config.persistSecrets?.invoke(ProviderSecrets.EMPTY)
            return emptyList()
        }
        accessToken = tokens.accessToken
        persistRotated(tokens, config)
        return runCatching { api.search(query, tokens.accessToken, region) }.getOrDefault(emptyList())
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

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val hint = ref.deepLinkHint ?: return null
        return Launcher.viewIntent(context, hint, packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
