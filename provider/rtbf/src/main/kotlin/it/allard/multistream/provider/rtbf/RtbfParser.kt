package it.allard.multistream.provider.rtbf

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.int
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Parse RTBF Auvio content lists. The search page nests items under `data[].content` (a list of
 * blocks: PROGRAM_LIST, MEDIA_LIST, ...), while a category widget nests them under `data.content`
 * (a single object). Each item carries an id, title, type (SHOW/SERIE => series) and a `path` that
 * becomes the auvio.rtbf.be deep link. The QUICK_LINK block (categories) has no title and is skipped.
 */
object RtbfParser {
    fun parse(root: JsonObject): List<UnifiedSearchResult> {
        val out = LinkedHashMap<String, UnifiedSearchResult>()
        for (block in root["data"].array() ?: return emptyList()) {
            addItems(block.obj()?.get("content").array(), out)
        }
        return out.values.toList()
    }

    /** Parse a single category widget, whose items live under `data.content` (an object, not a list). */
    fun parseWidget(root: JsonObject): List<UnifiedSearchResult> {
        val out = LinkedHashMap<String, UnifiedSearchResult>()
        addItems(root["data"].obj()?.get("content").array(), out)
        return out.values.toList()
    }

    private fun addItems(content: JsonArray?, out: MutableMap<String, UnifiedSearchResult>) {
        for (item in content ?: return) {
            val o = item.obj() ?: continue
            val title = o["title"].string()?.takeIf { it.isNotBlank() } ?: continue
            val id = o["id"].string() ?: o["id"].int()?.toString() ?: continue
            val path = o["path"].string() ?: continue
            val type = o["type"].string()
            val media = if (type == "SHOW" || type == "SERIE" || type == "SERIES") MediaType.SERIES else MediaType.MOVIE
            // illustration is a size map (xs/s/m/l/xl); take a mid size for the poster.
            val illustration = o["illustration"].obj()
            val poster = illustration?.let { it["m"].string() ?: it["s"].string() ?: it["xs"].string() }
            out.putIfAbsent(
                id,
                UnifiedSearchResult(
                    provider = ProviderId.RTBF,
                    ref = ProviderRef(ProviderId.RTBF, id, "https://auvio.rtbf.be$path", Region("BE")),
                    title = title,
                    type = media,
                    posterUrl = poster,
                    availabilityType = AvailabilityType.FREE_ADS,
                ),
            )
        }
    }
}
