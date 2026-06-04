package it.allard.multistream.provider.api

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.ProviderRef

/** Low-level intent helpers shared by every provider. */
object Launcher {
    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** A VIEW intent pinned to [packageName]; null if it does not resolve to that app. */
    fun viewIntent(context: Context, uri: String, packageName: String): Intent? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    /** Bare launch, preferring the leanback (TV) entry then the normal launcher entry. */
    fun launchApp(context: Context, packageName: String): Intent? =
        (
            context.packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: context.packageManager.getLaunchIntentForPackage(packageName)
            )?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    fun playStoreIntent(packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** What the caller should do to honor a launch request. */
sealed interface LaunchAction {
    data class Start(val intent: Intent) : LaunchAction
    data class Install(val packageName: String, val intent: Intent) : LaunchAction
    data object Unavailable : LaunchAction
}

/**
 * Resolve the best launch action for a title on a provider, applying the fallback chain:
 * not-installed -> Play Store; episode deep-link -> title deep-link -> open-app(+search) -> bare launch.
 */
object LaunchResolver {
    fun resolve(
        context: Context,
        provider: StreamingProvider,
        ref: ProviderRef,
        episode: EpisodeCoord? = null,
        searchQuery: String? = null,
    ): LaunchAction {
        // A provider may have several installable packages (e.g. Prime's phone vs TV build); treat it
        // as installed if any is present, and only offer the Play Store when none is.
        val installedPkg = provider.launchPackages.firstOrNull { Launcher.isInstalled(context, it) }
        if (installedPkg == null) {
            val target = provider.launchPackages.first()
            return LaunchAction.Install(target, Launcher.playStoreIntent(target))
        }
        provider.buildLaunchIntent(context, ref, episode)?.let { return LaunchAction.Start(it) }
        if (episode != null) {
            provider.buildLaunchIntent(context, ref, null)?.let { return LaunchAction.Start(it) }
        }
        provider.launchAppFallback(context, searchQuery)?.let { return LaunchAction.Start(it) }
        Launcher.launchApp(context, installedPkg)?.let { return LaunchAction.Start(it) }
        return LaunchAction.Unavailable
    }
}
