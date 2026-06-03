package it.allard.multistream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * Hosts a WebView so the user can log into a provider's website; when the success cookie appears,
 * the captured cookie header is returned to the caller. Used by providers whose auth can't be done
 * with a plain password form (Netflix, Prime).
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
                if (cookies != null && cookies.contains("$successCookie=")) {
                    cookieManager.flush()
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_COOKIES, cookies))
                    finish()
                }
            }
        }
        setContentView(webView)
        webView.loadUrl(loginUrl)
    }

    companion object {
        private const val EXTRA_LOGIN_URL = "login_url"
        private const val EXTRA_COOKIE_URL = "cookie_url"
        private const val EXTRA_SUCCESS_COOKIE = "success_cookie"
        const val EXTRA_RESULT_COOKIES = "result_cookies"

        fun intent(context: Context, loginUrl: String, cookieUrl: String, successCookie: String): Intent =
            Intent(context, WebLoginActivity::class.java)
                .putExtra(EXTRA_LOGIN_URL, loginUrl)
                .putExtra(EXTRA_COOKIE_URL, cookieUrl)
                .putExtra(EXTRA_SUCCESS_COOKIE, successCookie)
    }
}
