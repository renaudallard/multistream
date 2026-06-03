package it.allard.multistream.provider.api

/**
 * Describes a WebView-based login for providers whose auth can't be replicated with a plain
 * email/password form (Netflix MSL, Amazon device registration). The app shows a WebView at
 * [loginUrl]; once [successCookie] appears for [cookieUrl], the captured cookie header is handed
 * back to the provider via [StreamingProvider.loginWithCookies].
 */
data class WebLoginSpec(
    val loginUrl: String,
    val cookieUrl: String,
    val successCookie: String,
)
