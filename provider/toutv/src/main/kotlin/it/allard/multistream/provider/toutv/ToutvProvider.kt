package it.allard.multistream.provider.toutv

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.Genre
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderSecrets
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.provider.api.WebLoginSpec
import it.allard.multistream.provider.api.runCatchingExceptCancellation
import java.net.URLEncoder
import java.util.UUID

/**
 * ICI Tou.tv, Radio-Canada's French streaming service (Quebec). Search and detail use the public
 * Radio-Canada OTT catalog API, which is anonymous and answers worldwide; playback stays in the
 * official app and is geo-locked to Canada. Launch opens the title page on ici.tou.tv in the Tou.tv
 * app. Region-independent (a single Canadian catalog).
 */
class ToutvProvider(
    private val api: ToutvApi = ToutvApi(),
) : StreamingProvider {
    override val id = ProviderId.TOUTV
    override val displayName = "ICI Tou.tv"
    override val packageName = "tv.tou.android"
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canGetDetails = true,
        canListEpisodes = true,
        canBrowseByGenre = true,
        canFetchWatchState = true,
        canDeepLinkToTitle = true,
        requiresAuth = false,
        optionalLogin = true,
    )

    override fun browsableGenres(): Set<Genre> = GENRE_SLUGS.keys

    override suspend fun browseByGenre(genre: Genre, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        val slug = GENRE_SLUGS[genre] ?: return emptyList()
        return api.browseByGenre(slug)
    }

    override fun supportedRegions(): Set<Region> = setOf(Region("CA"))

    /**
     * Optional Radio-Canada sign-in (Azure AD B2C, OIDC implicit flow). The access token comes back in
     * the redirect URL fragment, so the WebView login captures `access_token` from `auth-changed#...`
     * rather than a cookie. The token unlocks the member's account endpoints (watch progress); search
     * and detail stay anonymous.
     */
    override fun webLoginSpec(): WebLoginSpec {
        val scope = (listOf("openid", "offline_access") + RESOURCE_SCOPES.map { "$RESOURCE/$it" }).joinToString(" ")
        val authorize = "https://login.cbc.radio-canada.ca/$TENANT/B2C_1A_SSO_Login/oauth2/v2.0/authorize" +
            "?client_id=$CLIENT_ID" +
            "&redirect_uri=${enc(REDIRECT_URI)}" +
            "&response_type=${enc("id_token token")}" +
            "&response_mode=fragment" +
            "&scope=${enc(scope)}" +
            "&nonce=${UUID.randomUUID()}&state=${UUID.randomUUID()}&prompt=login&ui_locales=fr"
        return WebLoginSpec(
            loginUrl = authorize,
            cookieUrl = "https://ici.tou.tv",
            successCookie = "",
            autoCapture = false,
            tokenRedirectPrefix = REDIRECT_URI,
            tokenFragmentKey = "access_token",
        )
    }

    override suspend fun loginWithCookies(cookies: String): ProviderSecrets {
        // The captured value is the B2C access token (a JWT), not a cookie header.
        return ProviderSecrets(token = cookies)
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> =
        api.search(query)

    override suspend fun getDetails(ref: ProviderRef, config: ProviderConfig): ProviderTitleDetails? =
        runCatchingExceptCancellation { api.getDetails(ref.providerTitleId, ref) }.getOrNull()

    override suspend fun getSeasons(ref: ProviderRef, config: ProviderConfig): List<Season> =
        api.getSeasons(ref.providerTitleId)

    override suspend fun fetchWatchedEpisodes(ref: ProviderRef, config: ProviderConfig): List<EpisodeCoord> {
        val token = config.secrets.token ?: return emptyList()
        // Let failures (an expired access token answers 401) propagate so the import reports an
        // error instead of silently importing nothing.
        return api.fetchWatchedEpisodes(ref.providerTitleId, token)
    }

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: "https://ici.tou.tv/${ref.providerTitleId}"
        return Launcher.viewIntent(context, url, packageName)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        // Canonical genre -> Tou.tv category slug (from the catalog browse `genres[]`; Kids has no
        // matching genre category and is omitted).
        val GENRE_SLUGS = mapOf(
            Genre.COMEDY to "comedie-et-humour",
            Genre.DRAMA to "drame",
            Genre.HORROR to "suspense-et-horreur",
            Genre.ACTION to "action-et-aventure",
            Genre.DOCUMENTARY to "docu-realite",
            Genre.SCIFI to "science-fiction-et-fantastique",
            Genre.CRIME to "crime-et-police",
            Genre.ROMANCE to "romance",
            Genre.ANIMATION to "animation",
        )

        // ICI Tou.tv's Azure AD B2C tenant/policy/client (the values the ici.tou.tv web app uses).
        const val TENANT = "bef1b538-1950-4283-9b27-b096cbc18070"
        const val CLIENT_ID = "ebe6e7b0-3cc3-463d-9389-083c7b24399c"
        const val REDIRECT_URI = "https://ici.tou.tv/auth-changed"
        const val RESOURCE = "https://rcmnb2cprod.onmicrosoft.com/84593b65-0ef6-4a72-891c-d351ddd50aab"
        val RESOURCE_SCOPES = listOf(
            "oidc4ropc", "profile", "email", "id.write", "media-validation", "media-drmt",
            "toutv-presentation", "toutv-profiling", "subscriptions.write", "subscriptions.validate",
            "id.account.info", "toutv",
        )
    }
}
