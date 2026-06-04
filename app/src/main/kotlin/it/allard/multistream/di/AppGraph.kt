package it.allard.multistream.di

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import it.allard.multistream.core.data.CacheRepository
import it.allard.multistream.core.data.SecretStore
import it.allard.multistream.core.data.SettingsRepository
import it.allard.multistream.core.data.WatchRepository
import it.allard.multistream.core.data.db.DatabaseFactory
import it.allard.multistream.core.data.db.MultistreamDatabase
import it.allard.multistream.domain.SearchInteractor
import it.allard.multistream.launch.LaunchController
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.provider.arte.ArteProvider
import it.allard.multistream.provider.disney.DisneyProvider
import it.allard.multistream.provider.molotov.MolotovProvider
import it.allard.multistream.provider.netflix.NetflixProvider
import it.allard.multistream.provider.plex.PlexProvider
import it.allard.multistream.provider.prime.PrimeVideoProvider
import it.allard.multistream.provider.rtbf.RtbfProvider
import it.allard.multistream.provider.rtl.RtlProvider
import it.allard.multistream.provider.zattoo.ZattooProvider

/**
 * Hand-written dependency graph (no DI framework). Every singleton is lazy so that constructing the
 * graph in [it.allard.multistream.MultistreamApp.onCreate] does no disk/keystore work — a failing
 * component is built (and can fail gracefully) only when first used, never at app launch.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val database: MultistreamDatabase by lazy { DatabaseFactory.create(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val secrets: SecretStore by lazy { SecretStore(appContext) }
    val watchRepository: WatchRepository by lazy { WatchRepository(database) }
    val cacheRepository: CacheRepository by lazy { CacheRepository(database) }

    val providers: List<StreamingProvider> by lazy {
        listOf(
            NetflixProvider(),
            DisneyProvider(),
            PrimeVideoProvider(),
            MolotovProvider(),
            ZattooProvider(),
            ArteProvider(),
            PlexProvider(),
            RtbfProvider(),
            RtlProvider(),
        )
    }

    val registry: ProviderRegistry by lazy { ProviderRegistry(providers, settings) }
    val searchInteractor: SearchInteractor by lazy { SearchInteractor(registry, settings, secrets) }
    val launchController: LaunchController by lazy { LaunchController(appContext) }
}

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }
