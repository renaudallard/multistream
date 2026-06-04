package it.allard.multistream.provider.disney

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.Episode
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.int
import it.allard.multistream.core.net.long
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Parse Disney+ explore search responses: data.page.containers[].items[] where each item carries
 * id + visuals (title, release year, artwork). The id maps to the `entity-<id>` deep link.
 */
object DisneyParser {
    fun parseSearch(root: JsonObject, region: Region): List<UnifiedSearchResult> {
        val containers = root["data"].obj()?.get("page").obj()?.get("containers").array() ?: return emptyList()
        val out = LinkedHashMap<String, UnifiedSearchResult>()
        for (container in containers) {
            val items = container.obj()?.get("items").array() ?: continue
            for (item in items) {
                val result = (item as? JsonObject)?.let { toResult(it, region) } ?: continue
                out.putIfAbsent(result.ref.providerTitleId, result)
            }
        }
        return out.values.toList()
    }

    private fun toResult(item: JsonObject, region: Region): UnifiedSearchResult? {
        val id = item["id"].string() ?: return null
        val visuals = item["visuals"].obj() ?: return null
        val title = visuals["title"].string()?.takeIf { it.isNotBlank() } ?: return null
        val year = visuals["metastringParts"].obj()?.get("releaseYearRange").obj()?.get("startYear").int()
        val poster = disneyPoster(visuals["artwork"])
        return UnifiedSearchResult(
            provider = ProviderId.DISNEY,
            ref = ProviderRef(ProviderId.DISNEY, id, "https://www.disneyplus.com/browse/entity-$id", region),
            title = title,
            type = MediaType.SERIES,
            year = year,
            posterUrl = poster,
            availabilityType = AvailabilityType.SUBSCRIPTION,
        )
    }

    data class SeasonRef(val id: String, val number: Int, val name: String?)

    /** Entity page -> the `episodes` container's season list. */
    fun parseSeasonRefs(page: JsonObject): List<SeasonRef> {
        val containers = page["data"].obj()?.get("page").obj()?.get("containers").array() ?: return emptyList()
        val episodes = containers.firstOrNull { it.obj()?.get("type").string() == "episodes" }?.obj() ?: return emptyList()
        val seasons = episodes["seasons"].array() ?: return emptyList()
        return seasons.mapIndexedNotNull { index, season ->
            val obj = season.obj() ?: return@mapIndexedNotNull null
            val id = obj["id"].string() ?: return@mapIndexedNotNull null
            SeasonRef(id, index + 1, obj["visuals"].obj()?.get("name").string())
        }
    }

    /** Season page (`data.season.items`) -> episodes. */
    fun parseEpisodes(seasonPage: JsonObject, seasonNumber: Int): List<Episode> {
        val items = seasonPage["data"].obj()?.get("season").obj()?.get("items").array() ?: return emptyList()
        return items.mapNotNull { item ->
            val visuals = (item as? JsonObject)?.get("visuals").obj() ?: return@mapNotNull null
            val number = visuals["episodeNumber"].int() ?: return@mapNotNull null
            Episode(
                seasonNumber = seasonNumber,
                episodeNumber = number,
                title = visuals["episodeTitle"].string(),
                synopsis = visuals["description"].obj()?.get("brief").string(),
                runtimeMin = visuals["durationMs"].long()?.let { (it / 60_000).toInt() },
            )
        }
    }

    // Aspect-ratio tile keys, portrait (poster) shapes first.
    private val PORTRAIT_TILES = listOf("0.67", "0.71", "0.75")

    /**
     * Disney artwork holds imageId UUIDs per shape (`standard.tile["0.67"].imageId`), not URLs. Pick a
     * portrait tile and build the ripcut CDN URL; fall back to any tile shape, then to a raw URL.
     */
    private fun disneyPoster(artwork: JsonElement?): String? {
        val tile = artwork.obj()?.get("standard").obj()?.get("tile").obj() ?: return firstUrl(artwork)
        val key = (PORTRAIT_TILES + tile.keys).firstOrNull { tile[it] != null } ?: return firstUrl(artwork)
        val imageId = tile[key].obj()?.get("imageId").string() ?: return firstUrl(artwork)
        return "https://prod-ripcut-delivery.disney-plus.net/v1/variant/disney/$imageId/scale" +
            "?width=400&aspectRatio=$key&format=jpeg"
    }

    /** Depth-first search of the artwork for the first http(s) URL string (Disney nests these deeply). */
    private fun firstUrl(element: JsonElement?): String? = when (element) {
        is JsonObject -> element.values.firstNotNullOfOrNull { firstUrl(it) }
        is kotlinx.serialization.json.JsonArray -> element.firstNotNullOfOrNull { firstUrl(it) }
        is kotlinx.serialization.json.JsonPrimitive -> element.string()?.takeIf { it.startsWith("http") }
        else -> null
    }
}
