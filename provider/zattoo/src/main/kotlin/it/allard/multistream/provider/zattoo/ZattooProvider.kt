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
import it.allard.multistream.provider.api.runCatchingExceptCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private val sessionMutex = Mutex()

    override suspend fun login(username: String, password: String): ProviderSecrets {
        api.login(username, password)
        // The session cookie is persisted alongside the credentials so a fresh process resumes the
        // live session; the password stays only as the fallback once the cookie session expires.
        return ProviderSecrets(cookie = api.exportSession(), extra = mapOf("email" to username, "password" to password))
    }

    override suspend fun ensureSession(config: ProviderConfig): SessionState {
        if (api.isLoggedIn()) return SessionState.Ready
        return sessionMutex.withLock {
            // Re-check inside the lock so concurrent callers don't each start a fresh login.
            if (api.isLoggedIn()) return@withLock SessionState.Ready
            val cookie = config.secrets.cookie
            if (cookie != null && runCatchingExceptCancellation { api.resumeSession(cookie) }.getOrDefault(false)) {
                return@withLock SessionState.Ready
            }
            val email = config.secrets.extra["email"]
            val password = config.secrets.extra["password"]
            if (email != null && password != null) {
                runCatchingExceptCancellation { api.login(email, password) }
                    .fold(
                        {
                            // Persist the rotated session cookie so the next process resumes it too.
                            config.persistSecrets?.invoke(
                                ProviderSecrets(cookie = api.exportSession(), extra = config.secrets.extra),
                            )
                            SessionState.Ready
                        },
                        { SessionState.NeedsLogin(it.message ?: "Login failed") },
                    )
            } else {
                SessionState.NeedsLogin("Zattoo login required")
            }
        }
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        return try {
            api.search(query, region)
        } catch (e: ZattooApiException) {
            if (e.authError) retryAfterAuth(query, region, config) else throw e
        }
    }

    /** The session expired server-side: drop it, re-login from stored credentials and search once more. */
    private suspend fun retryAfterAuth(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        api.invalidateSession()
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        return runCatchingExceptCancellation { api.search(query, region) }.getOrDefault(emptyList())
    }

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? =
        ref.deepLinkHint?.let { Launcher.viewIntent(context, it, packageName) }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
