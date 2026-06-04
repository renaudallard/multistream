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
import kotlinx.serialization.json.JsonObject

/**
 * Parse the RTBF Auvio search page: `data` is a list of blocks (PROGRAM_LIST, MEDIA_LIST,
 * MEDIA_PREMIUM_LIST, ...). Each content item carries an id, title, type (SHOW => series) and a
 * `path` that becomes the auvio.rtbf.be deep link. The QUICK_LINK block (categories) has no title
 * and is skipped.
 */
object RtbfParser {
    fun parse(root: JsonObject): List<UnifiedSearchResult> {
        val blocks = root["data"].array() ?: return emptyList()
        val out = LinkedHashMap<String, UnifiedSearchResult>()
        for (block in blocks) {
            val content = block.obj()?.get("content").array() ?: continue
            for (item in content) {
                val o = item.obj() ?: continue
                val title = o["title"].string()?.takeIf { it.isNotBlank() } ?: continue
                val id = o["id"].string() ?: o["id"].int()?.toString() ?: continue
                val path = o["path"].string() ?: continue
                val media = if (o["type"].string() == "SHOW") MediaType.SERIES else MediaType.MOVIE
                out.putIfAbsent(
                    id,
                    UnifiedSearchResult(
                        provider = ProviderId.RTBF,
                        ref = ProviderRef(ProviderId.RTBF, id, "https://auvio.rtbf.be$path", Region("BE")),
                        title = title,
                        type = media,
                        availabilityType = AvailabilityType.FREE_ADS,
                    ),
                )
            }
        }
        return out.values.toList()
    }
}
