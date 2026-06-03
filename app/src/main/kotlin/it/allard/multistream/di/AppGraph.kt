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
import it.allard.multistream.provider.disney.DisneyProvider
import it.allard.multistream.provider.molotov.MolotovProvider
import it.allard.multistream.provider.netflix.NetflixProvider
import it.allard.multistream.provider.prime.PrimeVideoProvider
import it.allard.multistream.provider.zattoo.ZattooProvider

/**
 * Hand-written dependency graph (no DI framework). Holds the app-wide singletons and composes
 * the five providers into the registry. This is the single place that knows the full provider set.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val database: MultistreamDatabase = DatabaseFactory.create(appContext)

    val settings = SettingsRepository(appContext)
    val secrets = SecretStore(appContext)
    val watchRepository = WatchRepository(database)
    val cacheRepository = CacheRepository(database)

    val providers: List<StreamingProvider> = listOf(
        NetflixProvider(),
        DisneyProvider(),
        PrimeVideoProvider(),
        MolotovProvider(),
        ZattooProvider(),
    )

    val registry = ProviderRegistry(providers, settings)
    val searchInteractor = SearchInteractor(registry, settings, secrets)
    val launchController = LaunchController(appContext)
}

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }
