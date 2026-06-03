package it.allard.multistream.domain

import it.allard.multistream.core.data.SecretStore
import it.allard.multistream.core.data.SettingsRepository
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Title
import it.allard.multistream.core.model.TitleKey
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.model.mergeResults
import it.allard.multistream.di.ProviderRegistry
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.StreamingProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Federated search: fan out across enabled+searchable providers in parallel (each guarded by a
 * timeout and try/catch so one slow/failed provider never breaks the others), then merge into
 * unified titles. In M0 the live providers return nothing and results come from [SampleCatalog].
 */
class SearchInteractor(
    private val registry: ProviderRegistry,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {
    private val index = ConcurrentHashMap<String, Title>()

    suspend fun search(query: String): List<Title> = coroutineScope {
        val providers = registry.searchable()
        val live = providers
            .map { provider -> async { runProviderSearch(provider, query) } }
            .awaitAll()
            .flatten()
        val merged = mergeResults(live + SampleCatalog.search(query))
        merged.forEach { index[it.key.serialize()] = it }
        merged
    }

    /** Resolve a full title (with seasons) for the detail screen. */
    suspend fun getTitle(key: TitleKey): Title? = SampleCatalog.byKey(key) ?: index[key.serialize()]

    private suspend fun runProviderSearch(provider: StreamingProvider, query: String): List<UnifiedSearchResult> =
        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            runCatching {
                val region = settings.region(provider.id)
                    ?: provider.supportedRegions().firstOrNull()
                    ?: Region("US")
                provider.search(query, region, ProviderConfig(region, enabled = true, secrets = secrets.read(provider.id)))
            }.getOrNull()
        } ?: emptyList()

    private companion object {
        const val PROVIDER_TIMEOUT_MS = 4_000L
    }
}
