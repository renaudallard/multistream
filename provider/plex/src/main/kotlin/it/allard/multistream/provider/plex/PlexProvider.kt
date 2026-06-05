package it.allard.multistream.provider.plex

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderSecrets
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.LinkSession
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.SessionState
import it.allard.multistream.provider.api.StreamingProvider

/**
 * Plex. Search works anonymously against Plex Discover. The optional login is the plex.tv/link device
 * flow (so it works with two-factor accounts): the app shows a code, the user enters it at
 * plex.tv/link, and the app then keeps the account token and auto-discovers the member's own Plex
 * Media Server (no token to paste). Search queries that server's library, falling back to a
 * personalized Discover search if the server is not reachable. Region-independent. Results deep-link
 * to watch.plex.tv when a slug is known, else open the Plex app.
 */
class PlexProvider(
    private val api: PlexApi = PlexApi(),
) : StreamingProvider {
    override val id = ProviderId.PLEX
    override val displayName = "Plex"
    override val packageName = "com.plexapp.android"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canGetDetails = true,
        canDeepLinkToTitle = true,
        requiresAuth = false,
        optionalLogin = true,
        linkLogin = true,
    )

    @Volatile
    private var server: String? = null

    @Volatile
    private var token: String? = null

    @Volatile
    private var accountToken: String? = null

    override suspend fun beginLink(): LinkSession {
        val pin = api.createPin()
        // Plex's OAuth flow: open app.plex.tv/auth with the code + device context embedded so the user
        // just approves (handles 2FA in the browser), then poll the PIN for the token.
        return LinkSession(
            code = pin.code,
            verificationUrl = "https://app.plex.tv/auth#?clientID=it.allard.multistream&code=${pin.code}" +
                "&context%5Bdevice%5D%5Bproduct%5D=multistream",
            awaitToken = {
                api.pollPin(pin.id, pin.code)?.let { account ->
                    accountToken = account
                    // Auto-discover the member's own server from the linked account token.
                    val connection = api.connectServer(account)
                    server = connection?.first
                    token = connection?.second ?: account
                    ProviderSecrets(
                        token = token!!,
                        extra = buildMap {
                            connection?.let { put("server", it.first) }
                            put("account", account)
                        },
                    )
                }
            },
        )
    }

    override suspend fun ensureSession(config: ProviderConfig): SessionState {
        if (token == null) {
            token = config.secrets.token
            server = config.secrets.extra["server"]
            accountToken = config.secrets.extra["account"]
        }
        // Search always works (anonymous Discover when logged out); a server is optional.
        return SessionState.Ready
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        ensureSession(config)
        val serverUrl = server
        val serverToken = token
        val discoverToken = accountToken ?: token
        return try {
            if (serverUrl != null && serverToken != null) {
                api.searchServer(serverUrl, serverToken, query)
            } else {
                api.search(query, discoverToken)
            }
        } catch (e: PlexApiException) {
            // A bad/unreachable server must never blank out search: fall back to Discover.
            runCatching { api.search(query, discoverToken) }.getOrDefault(emptyList())
        }
    }

    override suspend fun getDetails(ref: ProviderRef, config: ProviderConfig): ProviderTitleDetails? {
        ensureSession(config)
        val serverUrl = server ?: return null
        val serverToken = token ?: return null
        return runCatching { api.getDetails(serverUrl, serverToken, ref.providerTitleId, ref) }.getOrNull()
    }

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: return Launcher.launchApp(context, packageName)
        return Launcher.viewIntent(context, url, packageName) ?: Launcher.launchApp(context, packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)
}
