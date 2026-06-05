package it.allard.multistream.provider.prime

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.NetJson
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
     * Extract the body of each `<script type="text/template">` block with a single linear scan.
     * A regex with a lazy `(.*?)</script>` backtracks catastrophically on un-terminated openers (a
     * Prime captcha or error page with no closing tag), so this walks indexOf boundaries instead and
     * stops as soon as no further closing tag exists.
     */
    private fun templateBlocks(html: String): List<String> {
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
