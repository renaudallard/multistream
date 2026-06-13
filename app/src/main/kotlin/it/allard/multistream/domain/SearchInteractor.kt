package it.allard.multistream.domain

import it.allard.multistream.core.data.SecretStore
import it.allard.multistream.core.data.SettingsRepository
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.Genre
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.Title
import it.allard.multistream.core.model.TitleKey
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.model.mergeResults
import it.allard.multistream.core.model.mergeSeasons
import it.allard.multistream.core.model.rankByRelevance
import it.allard.multistream.di.ProviderRegistry
import it.allard.multistream.provider.api.ProviderConfig
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.provider.api.runCatchingExceptCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Collator
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * One emission of a streaming search: the titles merged so far, whether more are pending, and the
 * display names of providers whose search failed (error or timeout), so the UI can say a provider
 * is unavailable instead of silently showing fewer results.
 */
data class SearchUpdate(val results: List<Title>, val loading: Boolean, val failed: List<String> = emptyList())

/**
 * Federated search that streams results: each enabled+searchable provider runs in parallel (guarded
 * by a timeout/try-catch so one slow/failed provider never blocks the others), and the merged list
 * is re-emitted as each provider returns. In M4 the live providers are joined by [SampleCatalog].
 */
class SearchInteractor(
    private val registry: ProviderRegistry,
    private val settings: SettingsRepository,
    // A lazy accessor: resolving it builds the Keystore-backed store, which must not run on the main
    // thread, so the first touch happens inside the IO-dispatched search/detail work below.
    private val secrets: () -> SecretStore,
) {
    // Bounded LRU: the resolve-by-key cache for clicked results must not grow for the whole process.
    private val index: MutableMap<String, Title> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Title>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Title>?): Boolean = size > INDEX_CAPACITY
        },
    )

    fun search(rawQuery: String): Flow<SearchUpdate> = channelFlow {
        // Trim here so leading/trailing whitespace never reaches a provider API (some backends match
        // " term" or "term " differently); this guards every caller, not just the UI's submit().
        val query = rawQuery.trim()
        val providers = registry.searchable()
        val accumulated = mutableListOf<UnifiedSearchResult>()
        val failed = Collections.synchronizedList(mutableListOf<String>())
        synchronized(accumulated) { accumulated.addAll(SampleCatalog.search(query)) }

        emit(query, accumulated, loading = providers.isNotEmpty())
        // Each provider streams an incremental loading=true update as it returns; the parent waits for
        // all of them and then sends one terminal loading=false snapshot. Keeping the final emission
        // strictly last means a reordered child send can never leave the spinner stuck on.
        coroutineScope {
            for (provider in providers) {
                launch {
                    val results = runProviderSearch(provider, query)
                    if (results == null) failed.add(provider.displayName)
                    val snapshot = synchronized(accumulated) {
                        accumulated.addAll(results.orEmpty())
                        accumulated.toList()
                    }
                    send(SearchUpdate(mergeAndIndex(query, snapshot), loading = true, failed = failed.toList()))
                }
            }
        }
        if (providers.isNotEmpty()) {
            val snapshot = synchronized(accumulated) { accumulated.toList() }
            send(SearchUpdate(mergeAndIndex(query, snapshot), loading = false, failed = failed.toList()))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Browse titles for a genre with no text query: fans out to every enabled provider that can browse
     * that genre, exactly like [search], and merges by title. Results rank in merge (provider-priority)
     * order since there is no query to score against.
     */
    fun browseByGenre(genre: Genre): Flow<SearchUpdate> = channelFlow {
        val providers = registry.genreBrowsable().filter { genre in it.browsableGenres() }
        val accumulated = mutableListOf<UnifiedSearchResult>()
        val failed = Collections.synchronizedList(mutableListOf<String>())
        send(SearchUpdate(emptyList(), loading = providers.isNotEmpty()))
        coroutineScope {
            for (provider in providers) {
                launch {
                    val results = runProviderBrowse(provider, genre)
                    if (results == null) failed.add(provider.displayName)
                    val snapshot = synchronized(accumulated) {
                        accumulated.addAll(results.orEmpty())
                        accumulated.toList()
                    }
                    send(SearchUpdate(mergeAndIndexAlphabetical(snapshot), loading = true, failed = failed.toList()))
                }
            }
        }
        if (providers.isNotEmpty()) {
            val snapshot = synchronized(accumulated) { accumulated.toList() }
            send(SearchUpdate(mergeAndIndexAlphabetical(snapshot), loading = false, failed = failed.toList()))
        }
    }.flowOn(Dispatchers.IO)

    /** Resolve a title from the sample catalog or the last search results. */
    suspend fun getTitle(key: TitleKey): Title? = SampleCatalog.byKey(key) ?: index[key.serialize()]

    /** A resolved title plus whether the episode listing failed (vs the title having none). */
    data class TitleDetails(val title: Title, val episodesFailed: Boolean = false)

    /**
     * Resolve a title and enrich it from the providers: synopsis/cast/date from the best
     * detail-capable provider, and (for a series with none) its seasons from the best episode provider.
     */
    suspend fun loadDetails(key: TitleKey): TitleDetails? = withContext(Dispatchers.IO) {
        val base = getTitle(key) ?: return@withContext null
        coroutineScope {
            // Overlap the two network round-trips: when the search-guessed title already looks like a
            // series whose episodes we need, start enumerating them in parallel with the detail fetch
            // instead of waiting for detail first. If detail later corrects the type to a movie, the
            // speculative work is cancelled and discarded.
            val speculativeSeasons = if (base.type == MediaType.SERIES && base.seasons.isEmpty()) {
                async { fetchSeasons(base) }
            } else {
                null
            }

            var title = base
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

            var episodesFailed = false
            if (title.type == MediaType.SERIES && title.seasons.isEmpty()) {
                // Reuse the speculative fetch when it was started for this same title; otherwise (the
                // guess was a movie but detail says series) enumerate now.
                val perProvider = speculativeSeasons?.await() ?: fetchSeasons(title)
                // All providers erroring is a failed listing; an empty but successful one means the
                // title genuinely has no enumerable episodes, so no warning is warranted.
                episodesFailed = perProvider.isNotEmpty() && perProvider.all { it == null }
                val merged = mergeSeasons(perProvider.filterNotNull())
                if (merged.isNotEmpty()) title = title.copy(seasons = merged)
            } else {
                // Detail corrected the guess to a movie: drop the speculative episode fetch.
                speculativeSeasons?.cancel()
            }
            TitleDetails(title, episodesFailed)
        }
    }

    /**
     * Enumerate episodes on every provider that can list them, in parallel, and union the results: a
     * provider with the full run fills the gaps of one that only carries part of it. Null entries are
     * providers that failed (vs an empty list, which is a successful "no episodes").
     */
    private suspend fun fetchSeasons(title: Title): List<List<Season>?> = coroutineScope {
        title.episodeProviders().map { (provider, ref) ->
            async { orDefault(null) { provider.getSeasons(ref, configFor(provider, ref)) } }
        }.awaitAll()
    }

    private fun Title.detailProvider(): Pair<StreamingProvider, ProviderRef>? =
        availabilities.firstOrNull { registry.get(it.provider)?.capabilities?.canGetDetails == true }
            ?.let { a -> registry.get(a.provider)?.let { it to a.ref } }

    private fun Title.episodeProviders(): List<Pair<StreamingProvider, ProviderRef>> =
        availabilities.mapNotNull { a ->
            registry.get(a.provider)?.takeIf { it.capabilities.canListEpisodes }?.let { it to a.ref }
        }

    private fun Title.watchStateProvider(): Pair<StreamingProvider, ProviderRef>? =
        availabilities.firstOrNull { registry.get(it.provider)?.capabilities?.canFetchWatchState == true }
            ?.let { a -> registry.get(a.provider)?.let { it to a.ref } }

    /**
     * The episodes the user has already watched on a capable provider (Netflix), for importing.
     * Null when the provider call failed (network, expired session), so the UI can report a failed
     * import instead of presenting it as "nothing watched".
     */
    suspend fun fetchWatched(key: TitleKey): List<EpisodeCoord>? = withContext(Dispatchers.IO) {
        // A title evicted from the bounded index is a failed lookup, not "nothing watched": return
        // null so the UI reports an import failure rather than an empty result.
        val title = getTitle(key) ?: return@withContext null
        val (provider, ref) = title.watchStateProvider() ?: return@withContext emptyList()
        orDefault(null) { provider.fetchWatchedEpisodes(ref, configFor(provider, ref)) }
    }

    private suspend fun configFor(provider: StreamingProvider, ref: ProviderRef): ProviderConfig {
        val region = ref.region ?: settings.region(provider.id) ?: provider.supportedRegions().firstOrNull() ?: Region("US")
        return ProviderConfig(
            region,
            enabled = true,
            secrets = secrets().read(provider.id),
            persistSecrets = { secrets().write(provider.id, it) },
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

    // Locale-aware, case- and accent-friendly title sort for genre browse (there is no query to rank by).
    private val titleCollator = Collator.getInstance(Locale.FRENCH).also { it.strength = Collator.SECONDARY }

    private fun mergeAndIndexAlphabetical(results: List<UnifiedSearchResult>): List<Title> {
        val merged = mergeResults(results).sortedWith { a, b -> titleCollator.compare(a.primaryTitle, b.primaryTitle) }
        merged.forEach { index[it.key.serialize()] = it }
        return merged
    }

    /** The per-provider config from settings region + secrets (no specific ref), for search/browse. */
    private suspend fun configFor(provider: StreamingProvider): ProviderConfig {
        val region = settings.region(provider.id) ?: provider.supportedRegions().firstOrNull() ?: Region("US")
        return ProviderConfig(
            region,
            enabled = true,
            secrets = secrets().read(provider.id),
            persistSecrets = { secrets().write(provider.id, it) },
        )
    }

    /** Null = the provider failed or timed out, as opposed to finding nothing. */
    private suspend fun runProviderSearch(provider: StreamingProvider, query: String): List<UnifiedSearchResult>? =
        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            // runCatchingExceptCancellation lets a real cancellation (a superseded search) through
            // while still degrading a slow/failed provider to no results; the timeout cancellation is
            // caught by withTimeoutOrNull.
            runCatchingExceptCancellation {
                val config = configFor(provider)
                provider.search(query, config.region, config)
            }.getOrNull()
        }

    private suspend fun runProviderBrowse(provider: StreamingProvider, genre: Genre): List<UnifiedSearchResult>? =
        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            runCatchingExceptCancellation {
                val config = configFor(provider)
                provider.browseByGenre(genre, config.region, config)
            }.getOrNull()
        }

    private companion object {
        const val PROVIDER_TIMEOUT_MS = 8_000L
        const val INDEX_CAPACITY = 500
    }
}
