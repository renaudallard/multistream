package it.allard.multistream.provider.rtl

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderSecrets
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.provider.api.WebLoginSpec

/**
 * RTL Play (Belgian RTL, DPG Media). Its lfvp catalog API is geo-restricted to Belgium and sits
 * behind a JWT with a dynamically-resolved base URL, so search isn't reverse-engineered here. RTL is
 * added as launch + local tracking: the unified search shows a "Search in RTL Play" row that opens
 * the app, where the user searches directly. The optional login points at RTL's account SSO
 * (sso.rtl.be) and captures that session; search remains launch-only (the catalog API is JWT-gated).
 */
class RtlProvider : StreamingProvider {
    override val id = ProviderId.RTL
    override val displayName = "RTL Play"
    override val packageName = "com.tapptic.rtl.tvi"
    override val capabilities = ProviderCapabilities(
        canInAppSearchDeepLink = true,
        requiresAuth = false,
        optionalLogin = true,
    )

    override fun webLoginSpec(): WebLoginSpec = WebLoginSpec(
        loginUrl = "https://sso.rtl.be/",
        cookieUrl = "https://sso.rtl.be",
        successCookie = "",
        autoCapture = false,
    )

    override suspend fun loginWithCookies(cookies: String): ProviderSecrets = ProviderSecrets(cookie = cookies)

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? =
        Launcher.launchApp(context, packageName)

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.viewIntent(context, "https://www.rtlplay.be/", packageName)
            ?: Launcher.launchApp(context, packageName)
}
