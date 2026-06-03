package it.allard.multistream.core.data

import it.allard.multistream.core.data.db.CatalogCacheEntity
import it.allard.multistream.core.data.db.DetailCacheEntity
import it.allard.multistream.core.data.db.MultistreamDatabase
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.model.normalizeTitle
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Disposable cache of provider search/detail responses with a per-entry TTL.
 * Entirely separate from user watch data: wiping it never touches watch history.
 */
class CacheRepository(db: MultistreamDatabase) {
    private val dao = db.cacheDao()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun getSearch(provider: ProviderId, region: Region, query: String): List<UnifiedSearchResult>? {
        val entry = dao.getCatalog(provider.name, region.code, hash(query)) ?: return null
        if (isStale(entry.fetchedAt, entry.ttlMs)) return null
        return runCatching { json.decodeFromString<List<UnifiedSearchResult>>(entry.payloadJson) }.getOrNull()
    }

    suspend fun putSearch(
        provider: ProviderId,
        region: Region,
        query: String,
        results: List<UnifiedSearchResult>,
        ttlMs: Long,
    ) {
        dao.putCatalog(
            CatalogCacheEntity(provider.name, region.code, hash(query), json.encodeToString(results), now(), ttlMs),
        )
    }

    suspend fun getDetail(provider: ProviderId, providerTitleId: String, region: Region): ProviderTitleDetails? {
        val entry = dao.getDetail(provider.name, providerTitleId, region.code) ?: return null
        if (isStale(entry.fetchedAt, entry.ttlMs)) return null
        return runCatching { json.decodeFromString<ProviderTitleDetails>(entry.payloadJson) }.getOrNull()
    }

    suspend fun putDetail(provider: ProviderId, region: Region, details: ProviderTitleDetails, ttlMs: Long) {
        dao.putDetail(
            DetailCacheEntity(
                provider = provider.name,
                providerTitleId = details.ref.providerTitleId,
                region = region.code,
                payloadJson = json.encodeToString(details),
                fetchedAt = now(),
                ttlMs = ttlMs,
            ),
        )
    }

    suspend fun evictExpired() {
        val t = now()
        dao.evictCatalog(t)
        dao.evictDetail(t)
    }

    suspend fun wipe() {
        dao.wipeCatalog()
        dao.wipeDetail()
    }

    private fun hash(query: String): String = normalizeTitle(query).hashCode().toString()
    private fun isStale(fetchedAt: Long, ttlMs: Long): Boolean = fetchedAt + ttlMs < now()
    private fun now(): Long = System.currentTimeMillis()
}
