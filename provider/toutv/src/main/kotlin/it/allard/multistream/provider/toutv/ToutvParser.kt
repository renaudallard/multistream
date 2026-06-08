package it.allard.multistream.provider.toutv

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
import it.allard.multistream.core.net.bool
import it.allard.multistream.core.net.int
import it.allard.multistream.core.net.long
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonObject

/** The episode a member is currently resuming on a show, and whether that episode is finished. */
data class ToutvResume(val season: Int, val episode: Int, val completed: Boolean)

/**
 * Parser for ICI Tou.tv's Radio-Canada OTT catalog API. The search endpoint returns Show-level cards
 * (no episodes and no structured type); the show-detail endpoint carries the authoritative media type,
 * release year, synopsis and cast.
 */
object ToutvParser {
    private const val IMAGE_WIDTH = "360"

    fun parseSearch(root: JsonObject): List<UnifiedSearchResult> {
        val results = root["results"].array() ?: return emptyList()
        return results.mapNotNull { item ->
            val o = item.obj() ?: return@mapNotNull null
            // "Section" results are curated rows or live channels, not a watchable title; keep shows.
            if (o["type"].string() != "Show") return@mapNotNull null
            val title = o["title"].string()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val slug = o["url"].string()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Search carries no structured type; a "... saison(s)" subtitle marks a series, else a film.
            // getDetails corrects this from the show's schema.org @type when the title is opened.
            val series = o["infoTitle"].string()?.contains("saison", ignoreCase = true) == true
            UnifiedSearchResult(
                provider = ProviderId.TOUTV,
                ref = ProviderRef(ProviderId.TOUTV, slug, "https://ici.tou.tv/$slug", Region("CA")),
                title = title,
                type = if (series) MediaType.SERIES else MediaType.MOVIE,
                posterUrl = cardImage(o),
                availabilityType = AvailabilityType.SUBSCRIPTION,
            )
        }
    }

    fun parseDetails(root: JsonObject, ref: ProviderRef): ProviderTitleDetails? {
        val meta = root["structuredMetadata"].obj()
        // schema.org @type is the reliable movie-vs-series discriminator ("Movie" vs "TVSeries").
        val type = if (meta?.get("@type").string() == "Movie") MediaType.MOVIE else MediaType.SERIES
        val year = (meta?.get("datePublished").string() ?: meta?.get("startDate").string())
            ?.take(4)?.toIntOrNull()
        val cast = meta?.get("actor").array()
            ?.mapNotNull { it.obj()?.get("name").string()?.takeIf { name -> name.isNotBlank() } }
            ?.take(15).orEmpty()
        return ProviderTitleDetails(
            ref = ref,
            title = root["title"].string()?.takeIf { it.isNotBlank() } ?: ref.providerTitleId,
            type = type,
            year = year,
            synopsis = root["description"].string()?.takeIf { it.isNotBlank() }
                ?: meta?.get("abstract").string()?.takeIf { it.isNotBlank() },
            posterUrl = cardImage(root),
            cast = cast,
        )
    }

    private val SEASON_EPISODE = Regex("s(\\d+)e(\\d+)", RegexOption.IGNORE_CASE)

    /**
     * Seasons and episodes of a show detail. The response nests seasons under `content[].lineups[]`
     * (each lineup carries `seasonNumber`) and episodes under `lineup.items[]` (each with
     * `episodeNumber`, `completionTime` in ms, title, description, url). Extras with no episode number
     * are skipped. A detail returns the recent seasons it carries, not necessarily the whole run.
     */
    fun parseSeasons(detail: JsonObject): List<Season> {
        val bySeason = LinkedHashMap<Int, LinkedHashMap<Int, Episode>>()
        val seasonTitles = LinkedHashMap<Int, String?>()
        detail["content"].array()?.forEach { content ->
            content.obj()?.get("lineups").array()?.forEach { lineup ->
                val lineupObj = lineup.obj() ?: return@forEach
                val season = lineupObj["seasonNumber"].int() ?: return@forEach
                seasonTitles.putIfAbsent(season, lineupObj["title"].string())
                lineupObj["items"].array()?.forEach { item ->
                    val itemObj = item.obj() ?: return@forEach
                    val episode = itemObj["episodeNumber"].int() ?: return@forEach
                    bySeason.getOrPut(season) { LinkedHashMap() }.putIfAbsent(episode, episodeOf(itemObj, season, episode))
                }
            }
        }
        return bySeason.entries.sortedBy { it.key }
            .map { (season, episodes) -> Season(season, seasonTitles[season], episodes.values.sortedBy { it.episodeNumber }) }
    }

    private fun episodeOf(item: JsonObject, season: Int, episode: Int): Episode {
        val slug = item["url"].string()?.substringBefore('?')
        return Episode(
            seasonNumber = season,
            episodeNumber = episode,
            title = item["title"].string()?.takeIf { it.isNotBlank() },
            synopsis = item["description"].string()?.takeIf { it.isNotBlank() },
            runtimeMin = item["completionTime"].long()?.let { (it / 60_000).toInt() }?.takeIf { it > 0 },
            stillUrl = cardImage(item),
            providerRefs = slug?.let { listOf(ProviderRef(ProviderId.TOUTV, it, "https://ici.tou.tv/$it")) }.orEmpty(),
        )
    }

    /**
     * The resume point for [slug] from the member's `myview` (continue-watching) list: the one in-flight
     * episode per show. Its season/episode come from the item url (`<slug>/sNNeMM`), and `completed`
     * says whether that episode itself is finished. Returns null when the show is not in continue-watching.
     */
    fun parseResume(myview: JsonObject, slug: String): ToutvResume? {
        val item = myview["items"].array()?.mapNotNull { it.obj() }
            ?.firstOrNull { it["url"].string()?.substringBefore('?')?.startsWith("$slug/") == true } ?: return null
        val match = SEASON_EPISODE.find(item["url"].string()?.substringBefore('?').orEmpty()) ?: return null
        val completed = item["completionStatus"].obj()?.get("completed").bool() == true
        return ToutvResume(match.groupValues[1].toInt(), match.groupValues[2].toInt(), completed)
    }

    /** The card image URL with the literal "(_Size_)" placeholder filled with a pixel width. */
    private fun cardImage(node: JsonObject): String? =
        node["images"].obj()?.get("card").obj()?.get("url").string()?.replace("(_Size_)", IMAGE_WIDTH)
}
