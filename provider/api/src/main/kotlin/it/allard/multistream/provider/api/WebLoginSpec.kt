package it.allard.multistream.provider.api

/**
 * Describes a WebView-based login for providers whose auth can't be replicated with a plain
 * email/password form (Netflix MSL, Amazon device registration). The app shows a WebView at
 * [loginUrl]; once a cookie whose name starts with [successCookie] appears for [cookieUrl], the
 * captured cookie header is handed back to the provider via [StreamingProvider.loginWithCookies].
 * A name prefix (not an exact match) covers Amazon's region-suffixed cookies (at-main-av, at-acbde).
 * [logoutUrl], when set, is loaded first (with the existing session) so the provider signs out
 * server-side before a fresh login — needed for Netflix, which otherwise silently re-auths a stale
 * session that its API rejects.
 */
data class WebLoginSpec(
    val loginUrl: String,
    val cookieUrl: String,
    val successCookie: String,
    val logoutUrl: String? = null,
    // When false, the login is not auto-detected from [successCookie] (which would fire too early
    // for Netflix, whose NetflixId cookie is set on page load); the user confirms with the button.
    val autoCapture: Boolean = true,
    // For OAuth implicit flows (ICI Tou.tv's Azure AD B2C) the credential is a token in the redirect
    // URL fragment, not a cookie. When the WebView navigates to a URL starting with
    // [tokenRedirectPrefix], the value of [tokenFragmentKey] from the `#fragment` is captured and
    // handed to [StreamingProvider.loginWithCookies] instead of any cookie.
    val tokenRedirectPrefix: String? = null,
    val tokenFragmentKey: String? = null,
)
