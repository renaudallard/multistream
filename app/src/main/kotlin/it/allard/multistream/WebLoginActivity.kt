package it.allard.multistream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.ComponentActivity

/**
 * Hosts a WebView so the user can log into a provider's website; the captured cookie header is
 * returned to the caller. Used by providers whose auth can't be done with a plain password form
 * (Netflix, Prime). Amazon's sign-in spans several redirects and its session cookies are region
 * suffixed (at-main-av, at-acbde, ...), so [successCookie] is matched as a cookie-name prefix, and
 * a "Done" button lets the user finish manually if auto-detection misses.
 */
class WebLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL).orEmpty()
        val cookieUrl = intent.getStringExtra(EXTRA_COOKIE_URL).orEmpty()
        val successCookie = intent.getStringExtra(EXTRA_SUCCESS_COOKIE).orEmpty()
        if (loginUrl.isBlank()) {
            finish()
            return
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val webView = WebView(this)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val cookies = cookieManager.getCookie(cookieUrl)
                if (hasCookie(cookies, successCookie)) succeed(cookieManager, cookies)
            }
        }

        // Manual fallback: auto-detection keys off one cookie name, but Amazon's flow varies by
        // region and 2FA. The button hands over whatever cookies exist once the user is signed in.
        val doneButton = Button(this).apply {
            text = "I'm signed in — finish"
            setOnClickListener { succeed(cookieManager, cookieManager.getCookie(cookieUrl)) }
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(doneButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        layout.addView(webView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(layout)
        webView.loadUrl(loginUrl)
    }

    private fun succeed(cookieManager: CookieManager, cookies: String?) {
        if (cookies.isNullOrBlank()) return
        cookieManager.flush()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_COOKIES, cookies))
        finish()
    }

    companion object {
        private const val EXTRA_LOGIN_URL = "login_url"
        private const val EXTRA_COOKIE_URL = "cookie_url"
        private const val EXTRA_SUCCESS_COOKIE = "success_cookie"
        const val EXTRA_RESULT_COOKIES = "result_cookies"

        /** True when [cookieHeader] holds a cookie whose name starts with [name]. */
        private fun hasCookie(cookieHeader: String?, name: String): Boolean =
            name.isNotEmpty() &&
                cookieHeader?.split(';')?.any { it.substringBefore('=').trim().startsWith(name) } == true

        fun intent(context: Context, loginUrl: String, cookieUrl: String, successCookie: String): Intent =
            Intent(context, WebLoginActivity::class.java)
                .putExtra(EXTRA_LOGIN_URL, loginUrl)
                .putExtra(EXTRA_COOKIE_URL, cookieUrl)
                .putExtra(EXTRA_SUCCESS_COOKIE, successCookie)
    }
}
