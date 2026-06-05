package it.allard.multistream.provider.molotov

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
            posterUrl = posterImage(tile["image_bundle"]),
            synopsis = tile["description"].string()?.takeIf { it.isNotBlank() }
                ?: tile["description_formatter"].obj()?.get("format").string()?.takeIf { it.isNotBlank() },
            availabilityType = if (media == MediaType.LIVE_CHANNEL) AvailabilityType.LIVE else AvailabilityType.SUBSCRIPTION,
        )
    }

    private val IMAGE_SIZE = Regex("/(\\d+)x(\\d+)/")

    /** Collect the bundle's image URLs and prefer a portrait one (the art comes in several shapes). */
    private fun posterImage(element: JsonElement?): String? {
        val urls = mutableListOf<String>()
        collectUrls(element, urls)
        return urls.firstOrNull { isPortrait(it) } ?: urls.firstOrNull()
    }

    private fun collectUrls(element: JsonElement?, out: MutableList<String>) {
        when (element) {
            is JsonObject -> element.values.forEach { collectUrls(it, out) }
            is JsonArray -> element.forEach { collectUrls(it, out) }
            is JsonPrimitive -> element.string()?.takeIf { it.startsWith("http") }?.let { out.add(it) }
            else -> Unit
        }
    }

    private fun isPortrait(url: String): Boolean {
        val match = IMAGE_SIZE.find(url) ?: return false
        return match.groupValues[2].toInt() > match.groupValues[1].toInt()
    }
}
