package it.allard.multistream.provider.netflix

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.Episode
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderTitleDetails
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
    // Netflix boxart sizes: a true portrait poster (342x684) and a landscape fallback (665x375).
    const val ART_POSTER = "_342x684"
    const val ART_LANDSCAPE = "_665x375"

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

    /**
     * Title synopsis + year from the /metadata `video`; cast is resolved separately via Falcor and
     * passed in. Shows carry the year on the first season, movies on the video itself.
     */
    fun parseDetails(metadataRoot: JsonObject, cast: List<String>, ref: ProviderRef): ProviderTitleDetails {
        val video = metadataRoot["video"].obj()
        val media = when (video?.get("type").string()) {
            "movie", "supplemental" -> MediaType.MOVIE
            else -> MediaType.SERIES
        }
        val year = video?.get("year").int()
            ?: video?.get("seasons").array()?.firstOrNull().obj()?.get("year").int()
        return ProviderTitleDetails(
            ref = ref,
            title = video?.get("title").string() ?: "",
            type = media,
            year = year,
            synopsis = video?.get("synopsis").string()?.takeIf { it.isNotBlank() },
            cast = cast,
        )
    }

    /**
     * Resolve the Falcor cast refs: `videos[id].cast` is an index map `{ "0": ["person", pid], ... }`
     * into the top-level `person` map, where `person[pid].name` holds the actor name (billing order).
     */
    fun parseCast(jsonGraph: JsonObject, videoId: String): List<String> {
        val person = jsonGraph["person"].obj() ?: return emptyList()
        val castMap = atom(jsonGraph["videos"].obj()?.get(videoId).obj()?.get("cast")).obj() ?: return emptyList()
        return castMap.entries
            .filter { it.key.toIntOrNull() != null }
            .sortedBy { it.key.toInt() }
            .mapNotNull { (_, personRef) ->
                // Each entry is a Falcor ref `{value:["person", pid]}`; unwrap it to reach the id.
                val pid = atom(personRef).array()?.getOrNull(1).string() ?: return@mapNotNull null
                atom(person[pid].obj()?.get("name")).string()?.takeIf { it.isNotBlank() }
            }
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

    /** Map each requested id to its poster url from a boxarts pathEvaluator response (portrait first). */
    fun parseBoxarts(jsonGraph: JsonObject, ids: List<String>): Map<String, String> {
        val videos = jsonGraph["videos"].obj() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (id in ids) {
            val video = videos[id].obj() ?: continue
            (boxartUrl(video, ART_POSTER) ?: boxartUrl(video, ART_LANDSCAPE))?.let { out[id] = it }
        }
        return out
    }

    /**
     * Resolve a boxart URL from a materialized video. `boxarts[size].jpg` is an atom whose value is an
     * object `{url, image_key, isSmoky}` (older shapes were a plain url string); read `value.url`.
     */
    private fun boxartUrl(videoObj: JsonObject, size: String): String? {
        val value = atom(videoObj["boxarts"].obj()?.get(size).obj()?.get("jpg"))
        val url = value.obj()?.get("url").string() ?: value.string()
        return url?.takeIf { it.startsWith("http") }
    }

    /** Unwrap a jsonGraph atom (`{value: X}`) to its value, or return the element itself. */
    private fun atom(element: JsonElement?): JsonElement? = element.obj()?.get("value") ?: element
}
