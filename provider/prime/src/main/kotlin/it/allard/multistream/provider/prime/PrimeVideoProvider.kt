package it.allard.multistream.provider.prime

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.Genre
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderSecrets
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.provider.api.DeepLinks
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.SessionState
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.provider.api.WebLoginSpec

/**
 * Prime Video. Best-effort web search via cookies captured by a WebView login. The bundled APK is
 * the living-room (TV) build; on phones the mobile build is tried. Titles are keyed by ASIN/gti.
 */
class PrimeVideoProvider(
    private val api: PrimeApi = PrimeApi(),
) : StreamingProvider {
    override val id = ProviderId.PRIME
    override val displayName = "Prime Video"
    override val packageName = "com.amazon.amazonvideo.livingroom"
    // Phone build first (most devices), then the bundled TV "living-room" build.
    override val launchPackages = listOf(MOBILE_PACKAGE, packageName)
    override val capabilities = ProviderCapabilities(
        canSearch = true,
        canListEpisodes = true,
        canBrowseByGenre = true,
        canFetchWatchState = true,
        canDeepLinkToTitle = true,
        requiresAuth = true,
    )

    @Volatile
    private var cookies: String? = null

    override fun webLoginSpec(): WebLoginSpec = WebLoginSpec(
        loginUrl = "https://www.primevideo.com",
        cookieUrl = "https://www.primevideo.com",
        // Amazon's access-token cookie is region suffixed (at-main-av, at-acbde, ...); match the
        // prefix. It is set only once signed in, so it never triggers on an anonymous session.
        successCookie = "at-",
    )

    override suspend fun loginWithCookies(cookies: String): ProviderSecrets {
        this.cookies = cookies
        return ProviderSecrets(cookie = cookies)
    }

    override suspend fun ensureSession(config: ProviderConfig): SessionState {
        if (cookies == null) cookies = config.secrets.cookie
        return if (cookies != null) SessionState.Ready else SessionState.NeedsLogin("Prime Video login required")
    }

    override suspend fun search(query: String, region: Region, config: ProviderConfig): List<UnifiedSearchResult> =
        withSession(config) { cookie -> api.search(query, cookie, region) }

    override fun browsableGenres(): Set<Genre> = GENRE_KEYWORDS.keys

    // Prime has no genre catalog page; its search is genre-aware, so browse maps each canonical genre to
    // an English genre keyword (Amazon localizes the results to the member's catalog) and queries search.
    override suspend fun browseByGenre(genre: Genre, region: Region, config: ProviderConfig): List<UnifiedSearchResult> {
        val keyword = GENRE_KEYWORDS[genre] ?: return emptyList()
        return withSession(config) { cookie -> api.browseGenre(keyword, cookie, region) }
    }

    override suspend fun getSeasons(ref: ProviderRef, config: ProviderConfig): List<Season> =
        withSession(config) { cookie -> api.getSeasons(ref.providerTitleId, cookie) }

    override suspend fun fetchWatchedEpisodes(ref: ProviderRef, config: ProviderConfig): List<EpisodeCoord> =
        withSession(config) { cookie -> api.fetchWatchedEpisodes(ref.providerTitleId, cookie) }

    /**
     * Run [block] with the session cookie; empty without one. An auth error drops the session
     * before rethrowing, so the caller sees the failure and the next call asks for a login.
     */
    private suspend fun <T> withSession(config: ProviderConfig, block: suspend (String) -> List<T>): List<T> {
        if (ensureSession(config) !is SessionState.Ready) return emptyList()
        val cookie = cookies ?: return emptyList()
        return try {
            block(cookie)
        } catch (e: PrimeApiException) {
            if (e.authError) dropSession(config)
            throw e
        }
    }

    /**
     * Drop the session on an auth rejection. Clearing only the in-memory cookie is not enough:
     * ensureSession would reload the same dead cookie from the persisted secret on the next call,
     * so the expired session would silently return empty results forever instead of NeedsLogin.
     */
    private fun dropSession(config: ProviderConfig) {
        cookies = null
        config.persistSecrets?.invoke(ProviderSecrets.EMPTY)
    }

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: DeepLinks.primeDetail(ref.providerTitleId)
        return Launcher.viewIntent(context, url, packageName)
            ?: Launcher.viewIntent(context, url, MOBILE_PACKAGE)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName) ?: Launcher.launchApp(context, MOBILE_PACKAGE)

    private companion object {
        const val MOBILE_PACKAGE = "com.amazon.avod.thirdpartyclient"

        // Canonical genre -> English keyword for Prime's genre-aware search. English terms match across
        // regions; Amazon returns the member's localized catalog regardless of the keyword language.
        val GENRE_KEYWORDS = mapOf(
            Genre.COMEDY to "comedy",
            Genre.DRAMA to "drama",
            Genre.HORROR to "horror",
            Genre.ACTION to "action",
            Genre.DOCUMENTARY to "documentary",
            Genre.SCIFI to "science fiction",
            Genre.CRIME to "crime",
            Genre.ROMANCE to "romance",
            Genre.ANIMATION to "animation",
            Genre.KIDS to "kids",
        )
    }
}
