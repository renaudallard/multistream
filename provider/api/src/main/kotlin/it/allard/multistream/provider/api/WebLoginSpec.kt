package it.allard.multistream.provider.api

/**
 * Describes a WebView-based login for providers whose auth can't be replicated with a plain
 * email/password form (Netflix MSL, Amazon device registration). The app shows a WebView at
 * [loginUrl]; once a cookie whose name starts with [successCookie] appears for [cookieUrl], the
 * captured cookie header is handed back to the provider via [StreamingProvider.loginWithCookies].
 * A name prefix (not an exact match) covers Amazon's region-suffixed cookies (at-main-av, at-acbde).
 */
data class WebLoginSpec(
    val loginUrl: String,
    val cookieUrl: String,
    val successCookie: String,
)
