package it.allard.multistream.provider.molotov

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.Episode
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
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
    // A single-genre browse nests its tiles under `section` (singular); search and category pages use
    // `sections`/`items`/`results`/`catalog`.
    private val CONTAINER_KEYS = listOf("sections", "section", "items", "results", "catalog")

    // A "program" tile covers both films and series; its metadata category tells them apart. Molotov's
    // Films category is id 1 (verified against the live API), so a program in that category is a movie.
    private const val FILM_CATEGORY_ID = "1"

    // Cap recursion depth so a deeply nested response can't overflow the stack.
    private const val MAX_DEPTH = 100

    fun parse(root: JsonElement, region: Region): List<UnifiedSearchResult> {
        val tiles = mutableListOf<JsonObject>()
        collect(root, tiles)
        val seen = HashSet<String>()
        // Dedup by slug/id, not by the ref's title id: the same program carried on several channels
        // shares the slug but gets a channel-scoped title id.
        return tiles.mapNotNull { tile ->
            toResult(tile, region)?.takeIf { seen.add(tile["slug"].string() ?: tile["id"].string() ?: "") }
        }
    }

    private fun collect(element: JsonElement, out: MutableList<JsonObject>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonArray -> element.forEach { collect(it, out, depth + 1) }
            is JsonObject -> {
                if (element["type"].string() in CONTENT_TYPES && element["title"].string() != null) {
                    out.add(element)
                }
                CONTAINER_KEYS.forEach { key -> element[key]?.let { collect(it, out, depth + 1) } }
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
            // A program is a film only when its category says so; otherwise it is a series.
            "program" -> if (isFilm(tile)) MediaType.MOVIE else MediaType.SERIES
            else -> MediaType.SERIES
        }
        // A channel tile's metadata describes its current broadcast, not the tile itself, so the
        // program/channel ids and the program deep link only apply to content tiles.
        val metadata = tile["metadata"].obj().takeIf { media != MediaType.LIVE_CHANNEL }
        val programId = metadata?.get("program_id").string()
        val channelId = metadata?.get("channel_id").string()
        // Episode listing needs the channel-scoped program view endpoint, so both ids ride in the
        // title id when known; older slug-only refs simply cannot list episodes.
        val titleId = if (channelId != null && programId != null) "$channelId:$programId" else id
        // Deep link onto the program page. The Fubo-based Molotov app routes only three URL shapes
        // (manifest intent filters): www.molotov.tv/deeplink*, the molotov:// scheme, and
        // app.molotov.tv with any path AS LONG AS an `al` query param is present (the app checks only
        // that it is non-null). A www.molotov.tv/fr_fr/p/... link matches none of these, so it fell
        // through to a bare app launch onto the home screen. app.molotov.tv carries the same
        // /fr_fr/p/<program_id>/<slug> path the website resolves, so it lands on the show.
        val deepLink = if (programId != null && slug != null) {
            "https://app.molotov.tv/fr_fr/p/$programId/$slug?al=1"
        } else {
            null
        }
        return UnifiedSearchResult(
            provider = ProviderId.MOLOTOV,
            ref = ProviderRef(ProviderId.MOLOTOV, titleId, deepLink, region),
            title = title,
            type = media,
            posterUrl = posterImage(tile["image_bundle"]),
            synopsis = tile["description"].string()?.takeIf { it.isNotBlank() }
                ?: tile["description_formatter"].obj()?.get("format").string()?.takeIf { it.isNotBlank() },
            availabilityType = if (media == MediaType.LIVE_CHANNEL) AvailabilityType.LIVE else AvailabilityType.SUBSCRIPTION,
        )
    }

    /** A program tile is a film when its metadata category is Molotov's Films category. */
    private fun isFilm(tile: JsonObject): Boolean =
        tile["metadata"].obj()?.get("program_category_id").string() == FILM_CATEGORY_ID

    /**
     * Program view page (v2/channels/{channel}/programs/{program}/view) -> seasons with episodes.
     * Episode tiles carry their coordinates in `metadata` (season_number/episode_number as strings,
     * with the channel and program ids alongside); the subtitle's "SxxEyy - title" text is the
     * fallback. The page can also tile other programs (recommendations), so anything stamped with a
     * different program_id is skipped.
     */
    fun parseSeasons(root: JsonElement, programId: String): List<Season> {
        val tiles = mutableListOf<JsonObject>()
        collectEpisodeTiles(root, tiles)
        val episodes = tiles.mapNotNull { toEpisode(it, programId) }
            .distinctBy { it.seasonNumber to it.episodeNumber }
        return episodes.groupBy { it.seasonNumber }.toSortedMap().map { (number, list) ->
            Season(seasonNumber = number, episodes = list.sortedBy { it.episodeNumber })
        }
    }

    private fun collectEpisodeTiles(element: JsonElement, out: MutableList<JsonObject>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonArray -> element.forEach { collectEpisodeTiles(it, out, depth + 1) }
            is JsonObject -> {
                if (element["metadata"].obj()?.get("episode_number") != null) out.add(element)
                element.values.forEach { collectEpisodeTiles(it, out, depth + 1) }
            }
            else -> Unit
        }
    }

    private val SEASON_EPISODE = Regex("S(\\d+)E(\\d+)")

    private fun toEpisode(tile: JsonObject, programId: String): Episode? {
        val metadata = tile["metadata"].obj() ?: return null
        if (metadata["program_id"].string()?.let { it != programId } == true) return null
        val subtitle = tile["subtitle_formatter"].obj()?.get("format").string()
            ?: tile["subtitle"].string()
        val coded = subtitle?.let { SEASON_EPISODE.find(it) }
        val episode = metadata["episode_number"].string()?.toIntOrNull()
            ?: coded?.groupValues?.get(2)?.toIntOrNull() ?: return null
        val season = metadata["season_number"].string()?.toIntOrNull()
            ?: coded?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return Episode(
            seasonNumber = season,
            episodeNumber = episode,
            title = metadata["episode_title"].string()?.takeIf { it.isNotBlank() }
                ?: subtitle?.substringAfter(" - ", "")?.takeIf { it.isNotBlank() },
        )
    }

    private val IMAGE_SIZE = Regex("/(\\d+)x(\\d+)/")

    /** Collect the bundle's image URLs and prefer a portrait one (the art comes in several shapes). */
    private fun posterImage(element: JsonElement?): String? {
        val urls = mutableListOf<String>()
        collectUrls(element, urls)
        return urls.firstOrNull { isPortrait(it) } ?: urls.firstOrNull()
    }

    private fun collectUrls(element: JsonElement?, out: MutableList<String>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonObject -> element.values.forEach { collectUrls(it, out, depth + 1) }
            is JsonArray -> element.forEach { collectUrls(it, out, depth + 1) }
            is JsonPrimitive -> element.string()?.takeIf { it.startsWith("http") }?.let { out.add(it) }
            else -> Unit
        }
    }

    private fun isPortrait(url: String): Boolean {
        val match = IMAGE_SIZE.find(url) ?: return false
        // toLongOrNull: the regex allows arbitrarily long digit runs that would overflow toInt().
        val width = match.groupValues[1].toLongOrNull() ?: return false
        val height = match.groupValues[2].toLongOrNull() ?: return false
        return height > width
    }
}
