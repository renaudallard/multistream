package it.allard.multistream.domain

import it.allard.multistream.core.data.SecretStore
import it.allard.multistream.core.data.SettingsRepository
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Title
import it.allard.multistream.core.model.TitleKey
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.model.mergeResults
import it.allard.multistream.di.ProviderRegistry
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.StreamingProvider
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One emission of a streaming search: the titles merged so far, and whether more are pending. */
data class SearchUpdate(val results: List<Title>, val loading: Boolean)

/**
 * Federated search that streams results: each enabled+searchable provider runs in parallel (guarded
 * by a timeout/try-catch so one slow/failed provider never blocks the others), and the merged list
 * is re-emitted as each provider returns. In M4 the live providers are joined by [SampleCatalog].
 */
class SearchInteractor(
    private val registry: ProviderRegistry,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {
    private val index = ConcurrentHashMap<String, Title>()

    fun search(query: String): Flow<SearchUpdate> = channelFlow {
        val providers = registry.searchable()
        val accumulated = mutableListOf<UnifiedSearchResult>()
        synchronized(accumulated) { accumulated.addAll(SampleCatalog.search(query)) }
        val remaining = AtomicInteger(providers.size)

        emit(accumulated, loading = providers.isNotEmpty())
        for (provider in providers) {
            launch {
                val results = runProviderSearch(provider, query)
                val snapshot = synchronized(accumulated) {
                    accumulated.addAll(results)
                    accumulated.toList()
                }
                send(SearchUpdate(mergeAndIndex(snapshot), loading = remaining.decrementAndGet() > 0))
            }
        }
    }

    /** Resolve a title from the sample catalog or the last search results. */
    suspend fun getTitle(key: TitleKey): Title? = SampleCatalog.byKey(key) ?: index[key.serialize()]

    /** Like [getTitle], but enriches a series that has no seasons from the best episode provider. */
    suspend fun loadDetails(key: TitleKey): Title? {
        val base = getTitle(key) ?: return null
        if (base.seasons.isNotEmpty() || base.type != MediaType.SERIES) return base
        val availability = base.availabilities.firstOrNull {
            registry.get(it.provider)?.capabilities?.canListEpisodes == true
        } ?: return base
        val provider = registry.get(availability.provider) ?: return base
        val region = availability.ref.region
            ?: settings.region(provider.id)
            ?: provider.supportedRegions().firstOrNull()
            ?: Region("US")
        val config = ProviderConfig(
            region,
            enabled = true,
            secrets = secrets.read(provider.id),
            persistSecrets = { secrets.write(provider.id, it) },
        )
        val seasons = runCatching { provider.getSeasons(availability.ref, config) }.getOrDefault(emptyList())
        return if (seasons.isNotEmpty()) base.copy(seasons = seasons) else base
    }

    private suspend fun ProducerScope<SearchUpdate>.emit(results: List<UnifiedSearchResult>, loading: Boolean) {
        send(SearchUpdate(mergeAndIndex(synchronized(results) { results.toList() }), loading))
    }

    private fun mergeAndIndex(results: List<UnifiedSearchResult>): List<Title> {
        val merged = mergeResults(results)
        merged.forEach { index[it.key.serialize()] = it }
        return merged
    }

    private suspend fun runProviderSearch(provider: StreamingProvider, query: String): List<UnifiedSearchResult> =
        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            runCatching {
                val region = settings.region(provider.id)
                    ?: provider.supportedRegions().firstOrNull()
                    ?: Region("US")
                provider.search(
                    query,
                    region,
                    ProviderConfig(
                        region,
                        enabled = true,
                        secrets = secrets.read(provider.id),
                        persistSecrets = { secrets.write(provider.id, it) },
                    ),
                )
            }.getOrNull()
        } ?: emptyList()

    private companion object {
        const val PROVIDER_TIMEOUT_MS = 8_000L
    }
}
