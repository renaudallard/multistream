package it.allard.multistream.provider.molotov

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Tolerant walker for Molotov's section/tile search responses (shapes vary: sections/items/results
 * or a bare list). Collects content tiles and maps them to the unified model.
 */
object MolotovParser {
    private val CONTENT_TYPES = setOf("program", "vod", "episode", "season", "channel")
    private val CONTAINER_KEYS = listOf("sections", "items", "results", "catalog")

    fun parse(root: JsonElement, region: Region): List<UnifiedSearchResult> {
        val tiles = mutableListOf<JsonObject>()
        collect(root, tiles)
        val seen = HashSet<String>()
        return tiles.mapNotNull { toResult(it, region) }.filter { seen.add(it.ref.providerTitleId) }
    }

    private fun collect(element: JsonElement, out: MutableList<JsonObject>) {
        when (element) {
            is JsonArray -> element.forEach { collect(it, out) }
            is JsonObject -> {
                if (element["type"].string() in CONTENT_TYPES && element["title"].string() != null) {
                    out.add(element)
                }
                CONTAINER_KEYS.forEach { key -> element[key]?.let { collect(it, out) } }
            }
            else -> Unit
        }
    }

    private fun toResult(tile: JsonObject, region: Region): UnifiedSearchResult? {
        val title = tile["title"].string()?.takeIf { it.isNotBlank() } ?: return null
        val type = tile["type"].string() ?: return null
        val slug = tile["slug"].string()
        val id = slug ?: tile["id"].string() ?: return null
        val media = when (type) {
            "vod" -> MediaType.MOVIE
            "episode" -> MediaType.EPISODE
            "channel" -> MediaType.LIVE_CHANNEL
            else -> MediaType.SERIES
        }
        return UnifiedSearchResult(
            provider = ProviderId.MOLOTOV,
            ref = ProviderRef(ProviderId.MOLOTOV, id, slug?.let { "https://www.molotov.tv/$it" }, region),
            title = title,
            type = media,
            posterUrl = firstUrl(tile["image_bundle"]),
            availabilityType = if (media == MediaType.LIVE_CHANNEL) AvailabilityType.LIVE else AvailabilityType.SUBSCRIPTION,
        )
    }

    private fun firstUrl(element: JsonElement?): String? {
        when (element) {
            is JsonObject -> {
                element["url"].string()?.let { if (it.startsWith("http")) return it }
                element.values.forEach { firstUrl(it)?.let { url -> return url } }
            }
            is JsonArray -> element.forEach { firstUrl(it)?.let { url -> return url } }
            else -> Unit
        }
        return null
    }
}
