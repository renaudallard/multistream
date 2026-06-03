package it.allard.multistream.provider.netflix

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Extract matched titles from a Shakti Falcor `jsonGraph`. Rather than resolving Falcor references,
 * we read the `videos` map directly (id -> title/summary) which is sufficient for search results.
 * Leaves are jsonGraph atoms: `{"$type":"atom","value":X}`.
 */
object NetflixParser {
    fun parse(jsonGraph: JsonObject, region: Region): List<UnifiedSearchResult> {
        val videos = jsonGraph["videos"].obj() ?: return emptyList()
        val out = mutableListOf<UnifiedSearchResult>()
        for ((id, video) in videos) {
            val videoObj = video.obj() ?: continue
            val title = atom(videoObj["title"]).string()?.takeIf { it.isNotBlank() } ?: continue
            val type = atom(videoObj["summary"]).obj()?.get("type").string()
            val media = when (type) {
                "movie", "supplemental" -> MediaType.MOVIE
                else -> MediaType.SERIES
            }
            out.add(
                UnifiedSearchResult(
                    provider = ProviderId.NETFLIX,
                    ref = ProviderRef(ProviderId.NETFLIX, id, "https://www.netflix.com/title/$id", region),
                    title = title,
                    type = media,
                    availabilityType = AvailabilityType.SUBSCRIPTION,
                ),
            )
        }
        return out
    }

    /** Unwrap a jsonGraph atom (`{value: X}`) to its value, or return the element itself. */
    private fun atom(element: JsonElement?): JsonElement? = element.obj()?.get("value") ?: element
}
