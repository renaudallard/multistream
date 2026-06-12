package it.allard.multistream.provider.plex

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.Episode
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.int
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Tolerant Plex result parser. Walks the whole document for any object that is a movie/show with a
 * title and an id, so it handles both Discover (MediaContainer.SearchResults[].SearchResult[].Metadata)
 * and a Plex Media Server's search (MediaContainer.Hub[].Metadata[]). A `slug` yields a watch.plex.tv
 * deep link; server items have none and fall back to launching the Plex app. A server item's `thumb`
 * is a path on the server, so [imageBase] turns it into a loadable URL; the server access token is
 * never put in the URL (it would land in the database and the image cache) and is instead added as an
 * X-Plex-Token request header at load time via PlexImageAuth.
 */
object PlexParser {
    fun parse(root: JsonObject, imageBase: String? = null): List<UnifiedSearchResult> {
        val out = LinkedHashMap<String, UnifiedSearchResult>()
        collect(root, out, imageBase)
        return out.values.toList()
    }

    private fun collect(
        element: JsonElement,
        out: MutableMap<String, UnifiedSearchResult>,
        imageBase: String?,
    ) {
        when (element) {
            is JsonObject -> {
                val title = element["title"].string()
                val type = element["type"].string()
                val id = element["ratingKey"].string() ?: element["guid"].string()
                if (!title.isNullOrBlank() && id != null && (type == "movie" || type == "show")) {
                    val isShow = type == "show"
                    val slug = element["slug"].string()
                    val deepLink = slug?.let { "https://watch.plex.tv/${if (isShow) "show" else "movie"}/$it" }
                    out.putIfAbsent(
                        id,
                        UnifiedSearchResult(
                            provider = ProviderId.PLEX,
                            ref = ProviderRef(ProviderId.PLEX, id, deepLink),
                            title = title,
                            type = if (isShow) MediaType.SERIES else MediaType.MOVIE,
                            year = element["year"].int(),
                            posterUrl = posterUrl(element["thumb"].string(), imageBase),
                            availabilityType = AvailabilityType.UNKNOWN,
                        ),
                    )
                }
                element.values.forEach { collect(it, out, imageBase) }
            }
            is JsonArray -> element.forEach { collect(it, out, imageBase) }
            else -> Unit
        }
    }

    private fun posterUrl(thumb: String?, imageBase: String?): String? = when {
        thumb.isNullOrBlank() -> null
        thumb.startsWith("http") -> thumb
        imageBase != null -> "${imageBase.trimEnd('/')}$thumb"
        else -> null
    }

    /**
     * Watched episodes from a Plex Media Server's `/library/metadata/<ratingKey>/allLeaves` response
     * (`MediaContainer.Metadata[]`, one entry per episode). Each episode carries `parentIndex` (season
     * number), `index` (episode number) and `viewCount`; a Plex server counts an item as watched once
     * `viewCount > 0` (a partial resume sets only `viewOffset` and leaves `viewCount` at 0).
     */
    fun parseWatchedEpisodes(root: JsonObject): List<EpisodeCoord> =
        episodes(root).mapNotNull { episode ->
            val season = episode["parentIndex"].int() ?: return@mapNotNull null
            val number = episode["index"].int() ?: return@mapNotNull null
            if ((episode["viewCount"].int() ?: 0) > 0) EpisodeCoord(season, number) else null
        }

    /**
     * Seasons and their episodes from the same `allLeaves` response (`MediaContainer.Metadata[]`, one
     * entry per episode flattened across seasons). Episodes are grouped into one [Season] per distinct
     * `parentIndex` (season title from `parentTitle` when present), ordered by season then episode
     * number. `duration` is in milliseconds; each episode carries a per-episode [ProviderRef] (its own
     * `ratingKey`) so a single episode can be launched later.
     */
    fun parseSeasons(root: JsonObject): List<Season> =
        episodes(root).mapNotNull { episode ->
            val seasonNumber = episode["parentIndex"].int() ?: return@mapNotNull null
            val number = episode["index"].int() ?: return@mapNotNull null
            seasonNumber to Episode(
                seasonNumber = seasonNumber,
                episodeNumber = number,
                title = episode["title"].string()?.takeIf { it.isNotBlank() },
                synopsis = episode["summary"].string()?.takeIf { it.isNotBlank() },
                runtimeMin = episode["duration"].int()?.takeIf { it > 0 }?.let { it / 60000 },
                providerRefs = episode["ratingKey"].string()
                    ?.let { listOf(ProviderRef(ProviderId.PLEX, it)) }.orEmpty(),
            ) to episode["parentTitle"].string()?.takeIf { it.isNotBlank() }
        }
            .groupBy { it.first.first }
            .toSortedMap()
            .map { (seasonNumber, entries) ->
                Season(
                    seasonNumber = seasonNumber,
                    title = entries.firstNotNullOfOrNull { it.second },
                    episodes = entries.map { it.first.second }.sortedBy { it.episodeNumber },
                )
            }

    private fun episodes(root: JsonObject): List<JsonObject> =
        root["MediaContainer"].obj()?.get("Metadata").array()?.mapNotNull { it.obj() }.orEmpty()
}
