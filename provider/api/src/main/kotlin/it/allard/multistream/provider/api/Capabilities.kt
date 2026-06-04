package it.allard.multistream.provider.api

import it.allard.multistream.core.model.ProviderSecrets
import it.allard.multistream.core.model.Region

/**
 * What a provider can actually do. Capabilities drive graceful degradation: the UI reads these
 * flags and never assumes. A provider may support only launch + local tracking (everything false
 * except a deep-link flag) and the app still works.
 */
data class ProviderCapabilities(
    val canSearch: Boolean = false,
    val canGetDetails: Boolean = false,
    val canListEpisodes: Boolean = false,
    val canDeepLinkToTitle: Boolean = false,
    val canDeepLinkToEpisode: Boolean = false,
    val canDeepLinkToPlay: Boolean = false,
    val canInAppSearchDeepLink: Boolean = false,
    val isLiveTv: Boolean = false,
    val requiresRegion: Boolean = false,
    val requiresAuth: Boolean = false,
    // A login that is offered but not required — search works without it (Plex server, Arte/RTBF
    // accounts). The Settings login form is shown for requiresAuth || optionalLogin.
    val optionalLogin: Boolean = false,
    // The login is a device-link flow (show a code, the user enters it on a web page) rather than a
    // password form — used by Plex (plex.tv/link) so it works with two-factor accounts.
    val linkLogin: Boolean = false,
    // Labels for the two login fields (defaults suit an email/password form).
    val loginUserLabel: String = "Email",
    val loginPassLabel: String = "Password",
)

/** Per-provider runtime config, assembled from settings + the encrypted secret store. */
data class ProviderConfig(
    val region: Region,
    val enabled: Boolean,
    val secrets: ProviderSecrets,
    // Lets a provider write back a refreshed/rotated session so it survives restarts. Null = no-op.
    val persistSecrets: ((ProviderSecrets) -> Unit)? = null,
)

sealed interface SessionState {
    data object Ready : SessionState
    data object Anonymous : SessionState
    data class NeedsLogin(val reason: String) : SessionState
    data class Error(val cause: Throwable) : SessionState
}
