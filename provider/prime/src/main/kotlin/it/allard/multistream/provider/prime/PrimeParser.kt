package it.allard.multistream.provider.prime

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.Episode
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.bool
import it.allard.multistream.core.net.int
import it.allard.multistream.core.net.long
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Extract Prime Video search results from the embedded `text/template` JSON. Tolerant: any object
 * carrying a title plus a title identifier (gti/titleID/asin) is treated as a result.
 */
/** One entry of a series' season selector on the Prime detail page: a season and its detail link. */
data class PrimeSeasonLink(
    val sequenceNumber: Int,
    val link: String,
    val displayName: String?,
    val isSelected: Boolean,
)

object PrimeParser {
    private const val SCRIPT_CLOSE = "</script>"
    // Modern primevideo.com pages embed their state as JSON in this script block.
    const val HYDRATION_ID = "dv-web-page-hydration-data"
    private const val GTI_PREFIX = "amzn1.dv.gti."
    private val ID_KEYS = listOf("titleID", "titleId", "gti", "asin")

    // Watch-state fields the legacy (text/template) fallback still probes for.
    private val WATCHED_FLAG_KEYS = listOf("watched", "isWatched", "completed", "isCompleted", "hasWatched")
    private val SEASON_KEYS = listOf("seasonNumber", "seasonNum", "season")
    private val EPISODE_KEYS = listOf("episodeNumber", "episodeNum", "episode", "number", "sequenceNumber")

    // Per-episode descriptive fields. Prime's web detail page nests these under a variety of build
    // specific keys, so probe each candidate; the on-device "PrimeEpisodes" log confirms the real one.
    private val EPISODE_TITLE_KEYS = listOf("episodeTitle", "title", "displayTitle", "name", "heading")
    private val SYNOPSIS_KEYS = listOf("synopsis", "description", "shortSynopsis", "longSynopsis", "summary")
    // Runtime is variously in minutes or milliseconds; the *Ms keys are converted, the rest treated as
    // minutes (Amazon's detail blob commonly carries "runtime"/"duration" already in minutes).
    private val RUNTIME_MIN_KEYS = listOf("runtimeMinutes", "durationMinutes", "runtime", "duration", "runtimeSeconds")
    private val RUNTIME_MS_KEYS = listOf("runtimeMs", "durationMs", "runtimeMilliseconds", "durationMilliseconds")
    // A per-episode still/thumbnail.
    private val STILL_KEYS = listOf("stillUrl", "thumbnailUrl", "image", "imageUrl")

    // An episode counts as watched once its playback progress crosses this fraction (0..1), mirroring
    // Netflix's ~90% rule. Prime reports 1.0 for a fully-watched episode.
    private const val WATCHED_FRACTION = 0.9

    // Cap recursion depth: the search response is untrusted web JSON, and a deeply nested document
    // would otherwise overflow the stack. Real catalog payloads nest only a handful of levels.
    private const val MAX_DEPTH = 100

    fun parse(html: String, region: Region): List<UnifiedSearchResult> {
        val out = LinkedHashMap<String, UnifiedSearchResult>()
        // The search endpoint returns JSON (Accept: application/json); walk the whole document for
        // any object carrying a title + title id.
        runCatching { NetJson.parseToJsonElement(html) }.getOrNull()?.let { collect(it, region, out) }
        // Fall back to the older embedded text/template blocks if the JSON form yielded nothing.
        if (out.isEmpty()) {
            for (block in templateBlocks(html)) {
                runCatching { NetJson.parseToJsonElement(block) }.getOrNull()?.let { collect(it, region, out) }
            }
        }
        return out.values.toList()
    }

    /**
     * Episodes the signed-in member has watched on the detail page's selected season. On a modern
     * hydration page each episode's gti-keyed playback action carries a `progress` fraction (1.0 when
     * fully watched, `resumeTime` 0); an episode counts as watched once its progress crosses
     * [WATCHED_FRACTION]. The watched gtis are mapped back to episode numbers, paired with the page's
     * selected season. Only that season is on the page (the others are separate fetches via
     * [seasonLinks]). Older `text/template` pages fall through to the tolerant generic walk.
     */
    fun parseWatchedEpisodes(html: String): List<EpisodeCoord> {
        hydration(html)?.let { return watchedFromHydration(it) }
        val out = LinkedHashMap<String, EpisodeCoord>()
        forEachJsonRoot(html) { root -> collectWatched(root, out) }
        return out.values.toList()
    }

    private fun watchedFromHydration(root: JsonElement): List<EpisodeCoord> {
        val season = selectedSeasonNumber(root) ?: 1
        val episodeNumberByGti = HashMap<String, Int>()
        collectEpisodeNumbers(root, episodeNumberByGti)
        val watchedGtis = LinkedHashSet<String>()
        collectWatchedGtis(root, watchedGtis)
        val out = LinkedHashMap<String, EpisodeCoord>()
        for (gti in watchedGtis) {
            val episode = episodeNumberByGti[gti] ?: continue
            out.putIfAbsent("S${season}E$episode", EpisodeCoord(season, episode))
        }
        return out.values.toList()
    }

    /** Map each episode gti to its episode number from the hydration's episode nodes. */
    private fun collectEpisodeNumbers(element: JsonElement, out: MutableMap<String, Int>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    if (key.startsWith(GTI_PREFIX) && value is JsonObject &&
                        !value["titleType"].string().equals("season", true)
                    ) {
                        value["episodeNumber"].int()?.let { out.putIfAbsent(key, it) }
                    }
                    collectEpisodeNumbers(value, out, depth + 1)
                }
            }
            is JsonArray -> element.forEach { collectEpisodeNumbers(it, out, depth + 1) }
            else -> Unit
        }
    }

    /** Episode gtis whose playback action reports a watched-level progress fraction. */
    private fun collectWatchedGtis(element: JsonElement, out: MutableSet<String>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    if (key.startsWith(GTI_PREFIX) && value is JsonObject && maxProgress(value) >= WATCHED_FRACTION) {
                        out.add(key)
                    }
                    collectWatchedGtis(value, out, depth + 1)
                }
            }
            is JsonArray -> element.forEach { collectWatchedGtis(it, out, depth + 1) }
            else -> Unit
        }
    }

    /** The largest `progress` fraction anywhere in a subtree (0 when none is present). */
    private fun maxProgress(element: JsonElement, depth: Int = 0): Double {
        if (depth > MAX_DEPTH) return 0.0
        return when (element) {
            is JsonObject -> {
                val here = (element["progress"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                maxOf(here, element.values.maxOfOrNull { maxProgress(it, depth + 1) } ?: 0.0)
            }
            is JsonArray -> element.maxOfOrNull { maxProgress(it, depth + 1) } ?: 0.0
            else -> 0.0
        }
    }

    private fun collectWatched(element: JsonElement, out: MutableMap<String, EpisodeCoord>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonObject -> {
                val coord = episodeCoord(element)
                if (coord != null && isWatched(element)) {
                    out.putIfAbsent("S${coord.season}E${coord.episode}", coord)
                }
                element.values.forEach { collectWatched(it, out, depth + 1) }
            }
            is JsonArray -> element.forEach { collectWatched(it, out, depth + 1) }
            else -> Unit
        }
    }

    /** Read a season/episode coordinate from an object that looks like an episode node, or null. */
    private fun episodeCoord(obj: JsonObject): EpisodeCoord? {
        val episode = EPISODE_KEYS.firstNotNullOfOrNull { obj[it].int() } ?: return null
        // Episodes without an explicit season (flat lists) default to season 1.
        val season = SEASON_KEYS.firstNotNullOfOrNull { obj[it].int() } ?: 1
        return EpisodeCoord(season, episode)
    }

    /** Decide whether an episode node is watched from whichever watch field happens to be present. */
    private fun isWatched(obj: JsonObject): Boolean {
        if (WATCHED_FLAG_KEYS.any { obj[it].bool() == true }) return true
        // Percentage-style progress (0..100 or 0..1): watched once it crosses the threshold.
        obj["progressPercentage"].int()?.let { if (it >= WATCHED_FRACTION * 100) return true }
        obj["percentWatched"].int()?.let { if (it >= WATCHED_FRACTION * 100) return true }
        obj["progress"].int()?.let { if (it >= WATCHED_FRACTION * 100) return true }
        return false
    }

    /**
     * Episodes of the one season the detail page embeds. A modern primevideo.com detail page carries
     * its state as JSON in a `<script id="dv-web-page-hydration-data" type="application/json">` block:
     * the selected season's episodes are objects keyed by gti carrying an `episodeNumber`, and the
     * season number is on the sibling `titleType="season"` node (and the selected season-selector
     * entry). Only that season is inline; the others are separate fetches via [seasonLinks]. Older
     * pages that still embed `text/template` JSON fall through to the tolerant generic walk.
     */
    fun parseSeasons(html: String): List<Season> {
        hydrationSeason(html)?.let { return listOf(it) }
        // Fallback for any page still using the older embedded text/template JSON.
        val bySeason = LinkedHashMap<Int, LinkedHashMap<Int, Episode>>()
        forEachJsonRoot(html) { root -> collectEpisodes(root, bySeason) }
        return bySeason.entries
            .sortedBy { it.key }
            .map { (seasonNumber, episodes) ->
                Season(seasonNumber, title = null, episodes = episodes.values.sortedBy { it.episodeNumber })
            }
    }

    /** Every season the detail page's selector advertises, so the full run can be fetched a page at a time. */
    fun seasonLinks(html: String): List<PrimeSeasonLink> =
        hydration(html)?.let { seasonLinksFrom(it) } ?: emptyList()

    /** The single inline season of a hydration detail page (the selected season), or null. */
    private fun hydrationSeason(html: String): Season? {
        val root = hydration(html) ?: return null
        val season = selectedSeasonNumber(root) ?: 1
        val cardIds = cardTitleIds(root)
        val episodes = LinkedHashMap<Int, Episode>()
        collectHydrationEpisodes(root, season, cardIds, episodes)
        if (episodes.isEmpty()) return null
        val title = seasonLinksFrom(root).firstOrNull { it.sequenceNumber == season }?.displayName
        return Season(season, title, episodes.values.sortedBy { it.episodeNumber })
    }

    /** Parse the `dv-web-page-hydration-data` script body, or null when absent/unparseable. */
    private fun hydration(html: String): JsonElement? {
        val marker = html.indexOf(HYDRATION_ID)
        if (marker < 0) return null
        val open = html.indexOf('>', marker)
        if (open < 0) return null
        val close = html.indexOf(SCRIPT_CLOSE, open)
        if (close < 0) return null
        return runCatching { NetJson.parseToJsonElement(html.substring(open + 1, close)) }.getOrNull()
    }

    private fun seasonLinksFrom(root: JsonElement): List<PrimeSeasonLink> {
        val out = LinkedHashMap<Int, PrimeSeasonLink>()
        collectSeasonLinks(root, out)
        return out.values.sortedBy { it.sequenceNumber }
    }

    private fun collectSeasonLinks(element: JsonElement, out: MutableMap<Int, PrimeSeasonLink>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonObject -> {
                val link = element["seasonLink"].string()
                val seq = element["sequenceNumber"].int()
                if (link != null && seq != null) {
                    out.putIfAbsent(seq, PrimeSeasonLink(seq, link, element["displayName"].string(), element["isSelected"].bool() == true))
                }
                element.values.forEach { collectSeasonLinks(it, out, depth + 1) }
            }
            is JsonArray -> element.forEach { collectSeasonLinks(it, out, depth + 1) }
            else -> Unit
        }
    }

    /** The selected season's number: the selector's selected entry, else any `titleType="season"` node. */
    private fun selectedSeasonNumber(root: JsonElement): Int? {
        seasonLinksFrom(root).firstOrNull { it.isSelected }?.let { return it.sequenceNumber }
        var found: Int? = null
        fun walk(element: JsonElement, depth: Int) {
            if (found != null || depth > MAX_DEPTH) return
            when (element) {
                is JsonObject -> {
                    if (element["titleType"].string().equals("season", true)) {
                        element["seasonNumber"].int()?.let { found = it }
                    }
                    element.values.forEach { walk(it, depth + 1) }
                }
                is JsonArray -> element.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }
        walk(root, 0)
        return found
    }

    /** The authoritative gti list of the current season's episode cards, used to filter foreign nodes. */
    private fun cardTitleIds(root: JsonElement): Set<String> {
        val ids = LinkedHashSet<String>()
        fun walk(element: JsonElement, depth: Int) {
            if (depth > MAX_DEPTH) return
            when (element) {
                is JsonObject -> {
                    (element["cardTitleIds"] as? JsonArray)?.forEach { it.string()?.let(ids::add) }
                    element.values.forEach { walk(it, depth + 1) }
                }
                is JsonArray -> element.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }
        walk(root, 0)
        return ids
    }

    /** Walk the hydration tree for episode nodes (gti-keyed objects with an episodeNumber). */
    private fun collectHydrationEpisodes(
        element: JsonElement,
        season: Int,
        cardIds: Set<String>,
        out: MutableMap<Int, Episode>,
        depth: Int = 0,
    ) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    if (value is JsonObject && key.startsWith(GTI_PREFIX) &&
                        !value["titleType"].string().equals("season", true)
                    ) {
                        val epNum = value["episodeNumber"].int()
                        if (epNum != null && (cardIds.isEmpty() || key in cardIds)) {
                            out.putIfAbsent(epNum, buildHydrationEpisode(value, key, season, epNum))
                        }
                    }
                    collectHydrationEpisodes(value, season, cardIds, out, depth + 1)
                }
            }
            is JsonArray -> element.forEach { collectHydrationEpisodes(it, season, cardIds, out, depth + 1) }
            else -> Unit
        }
    }

    private fun buildHydrationEpisode(obj: JsonObject, gti: String, season: Int, epNum: Int): Episode {
        // `duration` is in seconds (the `runtime` field is a display string like "48 min").
        val runtimeMin = obj["duration"].int()?.let { it / 60 }?.takeIf { it > 0 }
        val images = obj["images"] as? JsonObject
        val still = (images?.get("covershot").string() ?: images?.get("packshot").string() ?: firstHttpUrl(images))
            ?.takeIf { it.startsWith("http") }
        return Episode(
            seasonNumber = season,
            episodeNumber = epNum,
            title = obj["title"].string()?.takeIf { it.isNotBlank() },
            synopsis = obj["synopsis"].string()?.takeIf { it.isNotBlank() },
            runtimeMin = runtimeMin,
            stillUrl = still,
            providerRefs = listOf(ProviderRef(ProviderId.PRIME, gti, "https://app.primevideo.com/detail?gti=$gti")),
        )
    }

    private fun collectEpisodes(
        element: JsonElement,
        bySeason: MutableMap<Int, LinkedHashMap<Int, Episode>>,
        depth: Int = 0,
    ) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonObject -> {
                val coord = episodeCoord(element)
                if (coord != null) {
                    val episode = buildEpisode(element, coord)
                    bySeason.getOrPut(coord.season) { LinkedHashMap() }.putIfAbsent(coord.episode, episode)
                }
                element.values.forEach { collectEpisodes(it, bySeason, depth + 1) }
            }
            is JsonArray -> element.forEach { collectEpisodes(it, bySeason, depth + 1) }
            else -> Unit
        }
    }

    /** Build an [Episode] from an episode-shaped node, pulling whichever descriptive keys are present. */
    private fun buildEpisode(obj: JsonObject, coord: EpisodeCoord): Episode {
        val runtimeMin = RUNTIME_MS_KEYS.firstNotNullOfOrNull { obj[it].long() }?.let { (it / 60_000).toInt() }
            ?: RUNTIME_MIN_KEYS.firstNotNullOfOrNull { key ->
                obj[key].int()?.let { if (key == "runtimeSeconds") it / 60 else it }
            }
        val id = ID_KEYS.firstNotNullOfOrNull { obj[it].string() }
        return Episode(
            seasonNumber = coord.season,
            episodeNumber = coord.episode,
            title = EPISODE_TITLE_KEYS.firstNotNullOfOrNull { obj[it].string()?.takeIf(String::isNotBlank) },
            synopsis = SYNOPSIS_KEYS.firstNotNullOfOrNull { obj[it].string()?.takeIf(String::isNotBlank) },
            runtimeMin = runtimeMin?.takeIf { it > 0 },
            stillUrl = STILL_KEYS.firstNotNullOfOrNull { obj[it].string()?.takeIf { url -> url.startsWith("http") } },
            providerRefs = id?.let {
                listOf(ProviderRef(ProviderId.PRIME, it, "https://app.primevideo.com/detail?gti=$it"))
            } ?: emptyList(),
        )
    }

    /** Parse every JSON root in the page (plain JSON body + each text/template block) and visit it. */
    private inline fun forEachJsonRoot(html: String, visit: (JsonElement) -> Unit) {
        runCatching { NetJson.parseToJsonElement(html) }.getOrNull()?.let(visit)
        for (block in templateBlocks(html)) {
            runCatching { NetJson.parseToJsonElement(block) }.getOrNull()?.let(visit)
        }
    }

    /**
     * Extract the body of each `<script type="text/template">` block with a single linear scan.
     * A regex with a lazy `(.*?)</script>` backtracks catastrophically on un-terminated openers (a
     * Prime captcha or error page with no closing tag), so this walks indexOf boundaries instead and
     * stops as soon as no further closing tag exists.
     */
    fun templateBlocks(html: String): List<String> {
        val blocks = ArrayList<String>()
        var i = 0
        while (true) {
            val tagStart = html.indexOf("<script", i, ignoreCase = true)
            if (tagStart < 0) break
            val tagEnd = html.indexOf('>', tagStart)
            if (tagEnd < 0) break
            val close = html.indexOf(SCRIPT_CLOSE, tagEnd + 1, ignoreCase = true)
            if (close < 0) break
            if (html.substring(tagStart, tagEnd + 1).contains("type=\"text/template\"", ignoreCase = true)) {
                blocks.add(html.substring(tagEnd + 1, close))
            }
            i = close + SCRIPT_CLOSE.length
        }
        return blocks
    }

    private fun collect(element: JsonElement, region: Region, out: MutableMap<String, UnifiedSearchResult>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonObject -> {
                val title = element["title"].string() ?: element["displayTitle"].string()
                val id = ID_KEYS.firstNotNullOfOrNull { element[it].string() }
                if (!title.isNullOrBlank() && id != null) {
                    // Prime tags results with entityType "Movie" / "TV Show" (older pages: contentType).
                    val type = element["entityType"].string() ?: element["contentType"].string()
                    val media = if (type.equals("Movie", ignoreCase = true)) MediaType.MOVIE else MediaType.SERIES
                    // Prime carries the poster inside an `images` object whose keys vary; walk it for
                    // the first image URL. Plain covers are forced to a big 16:9 (~1.8 MB), so shrink
                    // them; composited card images (`_CLs`) bake overlay coordinates at the original
                    // size, so resizing them breaks the composite and must be left alone.
                    val poster = (
                        element["image"].string()
                            ?: element["imageUrl"].string()
                            ?: firstHttpUrl(element["images"])
                        )?.let { url ->
                            if (url.contains("_CLs")) url else url.replace(Regex("_UR\\d+,\\d+_"), "_UR480,270_")
                        }
                    out.putIfAbsent(
                        id,
                        UnifiedSearchResult(
                            provider = ProviderId.PRIME,
                            ref = ProviderRef(ProviderId.PRIME, id, "https://app.primevideo.com/detail?gti=$id", region),
                            title = title,
                            type = media,
                            posterUrl = poster,
                            synopsis = element["synopsis"].string()?.takeIf { it.isNotBlank() },
                            availabilityType = AvailabilityType.SUBSCRIPTION,
                        ),
                    )
                }
                element.values.forEach { collect(it, region, out, depth + 1) }
            }
            is JsonArray -> element.forEach { collect(it, region, out, depth + 1) }
            else -> Unit
        }
    }

    /** Depth-first search of an arbitrary JSON value for the first http(s) URL string. */
    private fun firstHttpUrl(element: JsonElement?, depth: Int = 0): String? {
        if (depth > MAX_DEPTH) return null
        return when (element) {
            is JsonObject -> element.values.firstNotNullOfOrNull { firstHttpUrl(it, depth + 1) }
            is JsonArray -> element.firstNotNullOfOrNull { firstHttpUrl(it, depth + 1) }
            is JsonPrimitive -> element.string()?.takeIf { it.startsWith("http") }
            else -> null
        }
    }
}
