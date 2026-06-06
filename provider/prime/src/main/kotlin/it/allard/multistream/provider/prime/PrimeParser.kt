package it.allard.multistream.provider.prime

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
import it.allard.multistream.core.net.bool
import it.allard.multistream.core.net.int
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Extract Prime Video search results from the embedded `text/template` JSON. Tolerant: any object
 * carrying a title plus a title identifier (gti/titleID/asin) is treated as a result.
 */
object PrimeParser {
    private const val SCRIPT_CLOSE = "</script>"
    private val ID_KEYS = listOf("titleID", "titleId", "gti", "asin")

    // Candidate per-episode watch/progress fields Amazon is known to embed under various builds. We
    // do not know which (if any) the logged-in detail page actually exposes, so the diagnostic log
    // surfaces every one that appears; the watched-detection is tuned from that on-device evidence.
    private val PROGRESS_KEYS = listOf(
        "timecode", "resumeTime", "resumeTimecodeMs", "playbackPosition", "playbackTimeMs",
        "progress", "progressPercentage", "percentWatched", "watchedSeconds", "position",
    )
    private val WATCHED_FLAG_KEYS = listOf("watched", "isWatched", "completed", "isCompleted", "hasWatched")
    private val SEASON_KEYS = listOf("seasonNumber", "seasonNum", "season")
    private val EPISODE_KEYS = listOf("episodeNumber", "episodeNum", "episode", "number", "sequenceNumber")

    // An episode counts as watched once resume progress crosses this fraction of its runtime, mirroring
    // Netflix's ~90% rule. Used only when a percentage-style field is present; tuned from the log.
    private const val WATCHED_FRACTION = 0.9

    // Truncate the per-episode diagnostic so a huge detail blob never floods logcat.
    private const val DEBUG_CAP = 4000

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
     * Best-effort: episodes the signed-in member has watched, walked out of the logged-in detail
     * page. The exact shape is unknown without ATV device auth, so this is deliberately tolerant: any
     * JSON object that carries an episode number (and optionally a season number) plus one of the
     * known progress/watched fields is considered. An episode is marked watched when a boolean
     * watched-flag is true, or a percentage-style progress field crosses [WATCHED_FRACTION]. If the
     * detail page exposes no such field, this returns empty — see [watchDebug] for the raw evidence.
     */
    fun parseWatchedEpisodes(html: String): List<EpisodeCoord> {
        val out = LinkedHashMap<String, EpisodeCoord>()
        forEachJsonRoot(html) { root -> collectWatched(root, out) }
        return out.values.toList()
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
     * Compact per-episode diagnostic of every watch-related field present, truncated. This is the
     * point of the on-device run: it reveals which (if any) progress/watched field Prime actually
     * embeds per episode so the parser above can be tuned to the real key.
     */
    fun watchDebug(html: String): String {
        val sb = StringBuilder()
        var episodes = 0
        forEachJsonRoot(html) { root -> appendWatchDebug(root, sb, onEpisode = { episodes++ }) }
        val summary = if (episodes == 0) "no episode-shaped objects found" else "episodes=$episodes"
        val body = sb.toString().trim()
        val capped = if (body.length > DEBUG_CAP) body.substring(0, DEBUG_CAP) + "…(truncated)" else body
        return "$summary | $capped"
    }

    private fun appendWatchDebug(element: JsonElement, sb: StringBuilder, onEpisode: () -> Unit, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonObject -> {
                val coord = episodeCoord(element)
                if (coord != null) {
                    onEpisode()
                    val fields = (PROGRESS_KEYS + WATCHED_FLAG_KEYS)
                        .mapNotNull { k -> element[k]?.let { v -> "$k=${(v.string() ?: v.toString()).take(24)}" } }
                    sb.append("S${coord.season}E${coord.episode}{${fields.joinToString(",").ifEmpty { "noWatchFields" }}} ")
                }
                element.values.forEach { appendWatchDebug(it, sb, onEpisode, depth + 1) }
            }
            is JsonArray -> element.forEach { appendWatchDebug(it, sb, onEpisode, depth + 1) }
            else -> Unit
        }
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
