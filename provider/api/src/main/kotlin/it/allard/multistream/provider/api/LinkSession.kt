package it.allard.multistream.provider.api

import it.allard.multistream.core.model.ProviderSecrets

/**
 * A device-link login in progress. The UI shows [code] and tells the user to enter it at
 * [verificationUrl] (e.g. plex.tv/link), where they are already signed in so any 2FA is handled in
 * the browser. [awaitToken] suspends, polling the provider until the user links the code, then
 * returns the secrets to persist, or null on timeout.
 */
class LinkSession(
    val code: String,
    val verificationUrl: String,
    val awaitToken: suspend () -> ProviderSecrets?,
)
