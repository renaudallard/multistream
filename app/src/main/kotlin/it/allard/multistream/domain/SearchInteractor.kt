package it.allard.multistream.domain

import it.allard.multistream.core.data.SecretStore
import it.allard.multistream.core.data.SettingsRepository
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Title
import it.allard.multistream.core.model.TitleKey
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.model.mergeResults
import it.allard.multistream.core.model.rankByRelevance
import it.allard.multistream.di.ProviderRegistry
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.StreamingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

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
    // Bounded LRU: the resolve-by-key cache for clicked results must not grow for the whole process.
    private val index: MutableMap<String, Title> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Title>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Title>?): Boolean = size > INDEX_CAPACITY
        },
    )

    fun search(query: String): Flow<SearchUpdate> = channelFlow {
        val providers = registry.searchable()
        val accumulated = mutableListOf<UnifiedSearchResult>()
        synchronized(accumulated) { accumulated.addAll(SampleCatalog.search(query)) }
        val remaining = AtomicInteger(providers.size)

        emit(query, accumulated, loading = providers.isNotEmpty())
        for (provider in providers) {
            launch {
                val results = runProviderSearch(provider, query)
                synchronized(accumulated) { accumulated.addAll(results) }
                // Decide loading first, then snapshot: the last provider to finish (loading=false) then
                // observes every other provider's results, so the final emission can't drop any.
                val stillLoading = remaining.decrementAndGet() > 0
                val snapshot = synchronized(accumulated) { accumulated.toList() }
                send(SearchUpdate(mergeAndIndex(query, snapshot), loading = stillLoading))
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Resolve a title from the sample catalog or the last search results. */
    suspend fun getTitle(key: TitleKey): Title? = SampleCatalog.byKey(key) ?: index[key.serialize()]

    /**
     * Resolve a title and enrich it from the providers: synopsis/cast/date from the best
     * detail-capable provider, and (for a series with none) its seasons from the best episode provider.
     */
    suspend fun loadDetails(key: TitleKey): Title? = withContext(Dispatchers.IO) {
        var title = getTitle(key) ?: return@withContext null
        if (title.synopsis == null || title.cast.isEmpty()) {
            title.detailProvider()?.let { (provider, ref) ->
                orDefault(null) { provider.getDetails(ref, configFor(provider, ref)) }?.let { d ->
                    title = title.copy(
                        // The provider detail knows movie vs series authoritatively (search may guess);
                        // applying it stops films being sent to getSeasons for a phantom episode list.
                        type = d.type,
                        synopsis = title.synopsis ?: d.synopsis,
                        cast = title.cast.ifEmpty { d.cast },
                        year = title.year ?: d.year,
                        posterUrl = title.posterUrl ?: d.posterUrl,
                    )
                }
            }
        }
        if (title.type == MediaType.SERIES && title.seasons.isEmpty()) {
            title.episodeProvider()?.let { (provider, ref) ->
                val seasons = orDefault(emptyList()) { provider.getSeasons(ref, configFor(provider, ref)) }
                if (seasons.isNotEmpty()) title = title.copy(seasons = seasons)
            }
        }
        title
    }

    private fun Title.detailProvider(): Pair<StreamingProvider, ProviderRef>? =
        availabilities.firstOrNull { registry.get(it.provider)?.capabilities?.canGetDetails == true }
            ?.let { a -> registry.get(a.provider)?.let { it to a.ref } }

    private fun Title.episodeProvider(): Pair<StreamingProvider, ProviderRef>? =
        availabilities.firstOrNull { registry.get(it.provider)?.capabilities?.canListEpisodes == true }
            ?.let { a -> registry.get(a.provider)?.let { it to a.ref } }

    private suspend fun configFor(provider: StreamingProvider, ref: ProviderRef): ProviderConfig {
        val region = ref.region ?: settings.region(provider.id) ?: provider.supportedRegions().firstOrNull() ?: Region("US")
        return ProviderConfig(
            region,
            enabled = true,
            secrets = secrets.read(provider.id),
            persistSecrets = { secrets.write(provider.id, it) },
        )
    }

    /** Run an enrichment call, degrading to [default] on failure but letting cancellation propagate. */
    private suspend fun <T> orDefault(default: T, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            default
        }

    private suspend fun ProducerScope<SearchUpdate>.emit(query: String, results: List<UnifiedSearchResult>, loading: Boolean) {
        send(SearchUpdate(mergeAndIndex(query, synchronized(results) { results.toList() }), loading))
    }

    private fun mergeAndIndex(query: String, results: List<UnifiedSearchResult>): List<Title> {
        val merged = rankByRelevance(query, mergeResults(results))
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
        const val INDEX_CAPACITY = 500
    }
}
