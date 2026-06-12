package it.allard.multistream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Hosts a WebView so the user can log into a provider's website; the captured cookie header is
 * returned to the caller. Used by providers whose auth can't be done with a plain password form
 * (Netflix, Prime). Amazon's sign-in spans several redirects and its session cookies are region
 * suffixed (at-main-av, at-acbde, ...), so [successCookie] is matched as a cookie-name prefix, and
 * a "Done" button lets the user finish manually if auto-detection misses.
 */
class WebLoginActivity : ComponentActivity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The user types provider credentials here, so keep the screen out of screenshots, the
        // recents thumbnail and screen recordings.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL).orEmpty()
        val cookieUrl = intent.getStringExtra(EXTRA_COOKIE_URL).orEmpty()
        val successCookie = intent.getStringExtra(EXTRA_SUCCESS_COOKIE).orEmpty()
        val logoutUrl = intent.getStringExtra(EXTRA_LOGOUT_URL).orEmpty()
        val autoCapture = intent.getBooleanExtra(EXTRA_AUTO_CAPTURE, true)
        val tokenRedirectPrefix = intent.getStringExtra(EXTRA_TOKEN_REDIRECT)
        val tokenFragmentKey = intent.getStringExtra(EXTRA_TOKEN_FRAGMENT_KEY)
        if (loginUrl.isBlank()) {
            finish()
            return
        }

        val cookieManager = CookieManager.getInstance()

        val webView = WebView(this).also { this.webView = it }
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // When a logout URL is set, first load it (with the existing session so the server signs
        // out), then wipe local state and load the real login. Success is captured only after that.
        var loginPhase = logoutUrl.isBlank()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // OAuth implicit flow: the access token is in the redirect URL fragment, so capture it
                // as soon as the WebView navigates to the redirect target, before its page runs.
                if (loginPhase && tokenRedirectPrefix != null && tokenFragmentKey != null &&
                    url != null && url.startsWith(tokenRedirectPrefix)
                ) {
                    tokenFromFragment(url, tokenFragmentKey)?.let {
                        view?.stopLoading()
                        succeedWithValue(it)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!loginPhase) {
                    loginPhase = true
                    wipeAndLoad(cookieManager, webView, loginUrl)
                    return
                }
                if (!autoCapture) return
                val cookies = cookieManager.getCookie(cookieUrl)
                if (hasCookie(cookies, successCookie)) succeed(cookieManager, cookies)
            }
        }

        // Manual fallback: auto-detection keys off one cookie name, but Amazon's flow varies by
        // region and 2FA. The button hands over whatever cookies exist once the user is signed in.
        // Ignored during the logout phase: the cookies present then belong to the old session being
        // invalidated server-side and must not be captured as a login.
        val doneButton = Button(this).apply {
            text = getString(R.string.weblogin_finish)
            setOnClickListener {
                if (loginPhase) succeed(cookieManager, cookieManager.getCookie(cookieUrl))
            }
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(doneButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        layout.addView(webView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        // The activity draws edge-to-edge on Android 15+; inset the content below the status bar and
        // camera cutout so the finish button is on-screen and tappable.
        ViewCompat.setOnApplyWindowInsetsListener(layout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(layout)
        if (logoutUrl.isNotBlank()) {
            // Sign out server-side first, with the current session cookies still in place.
            cookieManager.setAcceptCookie(true)
            webView.loadUrl(logoutUrl)
        } else {
            wipeAndLoad(cookieManager, webView, loginUrl)
        }
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    /**
     * Wipe all prior session state (cookies, web storage, cache) and load [url] once the async
     * cookie clear finishes, so a stale session can't auto-log-in and get captured. Netflix
     * restores a session from cookies AND web storage, so both must go.
     */
    private fun wipeAndLoad(cookieManager: CookieManager, webView: WebView, url: String) {
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearHistory()
        cookieManager.removeAllCookies {
            // The clear is async; the user may have backed out before it finished, and the WebView
            // must not be touched after onDestroy has destroyed it.
            if (isDestroyed || isFinishing) return@removeAllCookies
            cookieManager.setAcceptCookie(true)
            webView.loadUrl(url)
        }
    }

    private fun succeed(cookieManager: CookieManager, cookies: String?) {
        if (cookies.isNullOrBlank()) return
        cookieManager.flush()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_COOKIES, cookies))
        finish()
    }

    /** Return the captured value (here an OAuth token from the redirect fragment) to the caller. */
    private fun succeedWithValue(value: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_COOKIES, value))
        finish()
    }

    companion object {
        private const val EXTRA_LOGIN_URL = "login_url"
        private const val EXTRA_COOKIE_URL = "cookie_url"
        private const val EXTRA_SUCCESS_COOKIE = "success_cookie"
        private const val EXTRA_LOGOUT_URL = "logout_url"
        private const val EXTRA_AUTO_CAPTURE = "auto_capture"
        private const val EXTRA_TOKEN_REDIRECT = "token_redirect"
        private const val EXTRA_TOKEN_FRAGMENT_KEY = "token_fragment_key"
        const val EXTRA_RESULT_COOKIES = "result_cookies"

        /** True when [cookieHeader] holds a cookie whose name starts with [name]. */
        private fun hasCookie(cookieHeader: String?, name: String): Boolean =
            name.isNotEmpty() &&
                cookieHeader?.split(';')?.any { it.substringBefore('=').trim().startsWith(name) } == true

        /** Extract a key's value from a URL's `#fragment` (e.g. access_token from an OAuth redirect). */
        private fun tokenFromFragment(url: String, key: String): String? {
            val fragment = url.substringAfter('#', "")
            if (fragment.isEmpty()) return null
            return fragment.split('&').firstNotNullOfOrNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0 || part.substring(0, eq) != key) {
                    null
                } else {
                    runCatching { java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                }
            }
        }

        fun intent(
            context: Context,
            loginUrl: String,
            cookieUrl: String,
            successCookie: String,
            logoutUrl: String? = null,
            autoCapture: Boolean = true,
            tokenRedirectPrefix: String? = null,
            tokenFragmentKey: String? = null,
        ): Intent =
            Intent(context, WebLoginActivity::class.java)
                .putExtra(EXTRA_LOGIN_URL, loginUrl)
                .putExtra(EXTRA_COOKIE_URL, cookieUrl)
                .putExtra(EXTRA_SUCCESS_COOKIE, successCookie)
                .putExtra(EXTRA_LOGOUT_URL, logoutUrl)
                .putExtra(EXTRA_AUTO_CAPTURE, autoCapture)
                .putExtra(EXTRA_TOKEN_REDIRECT, tokenRedirectPrefix)
                .putExtra(EXTRA_TOKEN_FRAGMENT_KEY, tokenFragmentKey)
    }
}
