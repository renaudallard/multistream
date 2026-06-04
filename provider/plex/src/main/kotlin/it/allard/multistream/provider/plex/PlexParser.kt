package it.allard.multistream.provider.plex

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.int
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonObject

/**
 * Parse Plex Discover search results: MediaContainer.SearchResults[].SearchResult[].Metadata. Each
 * Metadata carries title, type (movie/show), year, a ratingKey id and a slug for the watch deep link.
 */
object PlexParser {
    fun parse(root: JsonObject): List<UnifiedSearchResult> {
        val groups = root["MediaContainer"].obj()?.get("SearchResults").array() ?: return emptyList()
        val out = LinkedHashMap<String, UnifiedSearchResult>()
        for (group in groups) {
            val results = group.obj()?.get("SearchResult").array() ?: continue
            for (result in results) {
                val m = result.obj()?.get("Metadata").obj() ?: continue
                val title = m["title"].string()?.takeIf { it.isNotBlank() } ?: continue
                val id = m["ratingKey"].string() ?: m["guid"].string() ?: continue
                val isShow = m["type"].string() == "show"
                val slug = m["slug"].string()
                val deepLink = slug?.let { "https://watch.plex.tv/${if (isShow) "show" else "movie"}/$it" }
                out.putIfAbsent(
                    id,
                    UnifiedSearchResult(
                        provider = ProviderId.PLEX,
                        ref = ProviderRef(ProviderId.PLEX, id, deepLink),
                        title = title,
                        type = if (isShow) MediaType.SERIES else MediaType.MOVIE,
                        year = m["year"].int(),
                        posterUrl = m["thumb"].string(),
                        availabilityType = AvailabilityType.UNKNOWN,
                    ),
                )
            }
        }
        return out.values.toList()
    }
}
