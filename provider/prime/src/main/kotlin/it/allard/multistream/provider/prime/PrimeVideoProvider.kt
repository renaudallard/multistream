package it.allard.multistream.provider.prime

import android.content.Context
import android.content.Intent
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.provider.api.DeepLinks
import it.allard.multistream.provider.api.Launcher
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.StreamingProvider

/**
 * Prime Video. The bundled APK is the living-room (TV) build [packageName]; on phones the mobile
 * build [MOBILE_PACKAGE] is the real target, so both are tried. Catalog search (Amazon ATV) is not
 * attempted in v1; this is launch + local tracking. Titles are keyed by ASIN.
 */
class PrimeVideoProvider : StreamingProvider {
    override val id = ProviderId.PRIME
    override val displayName = "Prime Video"
    override val packageName = "com.amazon.amazonvideo.livingroom"
    override val capabilities = ProviderCapabilities(
        canDeepLinkToTitle = true,
    )

    override fun buildLaunchIntent(context: Context, ref: ProviderRef, episode: EpisodeCoord?): Intent? {
        val url = ref.deepLinkHint ?: DeepLinks.primeDetail(ref.providerTitleId)
        return Launcher.viewIntent(context, url, packageName)
            ?: Launcher.viewIntent(context, url, MOBILE_PACKAGE)
    }

    override fun launchAppFallback(context: Context, query: String?): Intent? =
        Launcher.launchApp(context, packageName) ?: Launcher.launchApp(context, MOBILE_PACKAGE)

    private companion object {
        const val MOBILE_PACKAGE = "com.amazon.avod.thirdpartyclient"
    }
}
