package it.allard.multistream.provider.netflix

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.Episode
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.int
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
    /**
     * Resolve the ordered search matches. The result list is reached through a chain of Falcor refs:
     * search.byTerm["|term"].titles[size] -> search.byReference[key] (a {index -> {reference -> videos[id]}}
     * map) -> videos[id]. We must follow it rather than read the flat `videos` map, which Netflix also
     * fills with unrelated home/billboard recommendations.
     */
    fun parse(jsonGraph: JsonObject, region: Region): List<UnifiedSearchResult> {
        val videos = jsonGraph["videos"].obj() ?: return emptyList()
        val search = jsonGraph["search"].obj() ?: return emptyList()
        val byReference = search["byReference"].obj() ?: return emptyList()
        val titlesRef = search["byTerm"].obj()?.values?.firstOrNull().obj()
            ?.get("titles").obj()?.values?.firstOrNull().obj()
        val listKey = titlesRef?.get("value").array()?.getOrNull(2).string() ?: return emptyList()
        val list = byReference[listKey].obj() ?: return emptyList()

        val out = mutableListOf<UnifiedSearchResult>()
        for ((indexKey, entry) in list.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }) {
            if (indexKey.toIntOrNull() == null) continue
            val id = entry.obj()?.get("reference").obj()?.get("value").array()?.getOrNull(1).string() ?: continue
            val videoObj = videos[id].obj() ?: continue
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

    /** Seasons + episodes from the /metadata response (`video.seasons[].episodes[]`, keyed by seq). */
    fun parseSeasons(root: JsonObject): List<Season> {
        val video = root["video"].obj() ?: root
        val seasons = video["seasons"].array() ?: return emptyList()
        return seasons.mapNotNull { season ->
            val seasonObj = season.obj() ?: return@mapNotNull null
            val seasonSeq = seasonObj["seq"].int() ?: return@mapNotNull null
            val episodes = seasonObj["episodes"].array()?.mapNotNull { episode ->
                val episodeObj = episode.obj() ?: return@mapNotNull null
                val episodeSeq = episodeObj["seq"].int() ?: return@mapNotNull null
                Episode(
                    seasonNumber = seasonSeq,
                    episodeNumber = episodeSeq,
                    title = episodeObj["title"].string(),
                    synopsis = episodeObj["synopsis"].string(),
                    runtimeMin = episodeObj["runtime"].int()?.let { it / 60 },
                )
            } ?: emptyList()
            Season(seasonSeq, seasonObj["longName"].string() ?: seasonObj["shortName"].string(), episodes)
        }
    }

    /** Unwrap a jsonGraph atom (`{value: X}`) to its value, or return the element itself. */
    private fun atom(element: JsonElement?): JsonElement? = element.obj()?.get("value") ?: element
}
