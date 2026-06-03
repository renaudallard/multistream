package it.allard.multistream.provider.zattoo

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.bool
import it.allard.multistream.core.net.int
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonElement

/**
 * Parse Zattoo's power_guide response and keep programs whose title matches the query.
 * Power-guide program fields: t=title, s=start, e=end, id=broadcastId.
 */
object ZattooParser {
    fun parsePowerGuide(root: JsonElement, query: String, region: Region, limit: Int = 60): List<UnifiedSearchResult> {
        val obj = root.obj() ?: return emptyList()
        if (obj["success"].bool() == false) return emptyList()
        val channels = obj["channels"].array() ?: return emptyList()
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()

        val out = LinkedHashMap<String, UnifiedSearchResult>()
        for (channel in channels) {
            val channelObj = channel.obj() ?: continue
            val cid = channelObj["cid"].string() ?: continue
            val programs = channelObj["programs"].array() ?: continue
            for (program in programs) {
                val programObj = program.obj() ?: continue
                val title = programObj["t"].string() ?: continue
                if (!title.lowercase().contains(needle)) continue
                val broadcastId = programObj["id"].string() ?: programObj["id"].int()?.toString() ?: continue
                val key = "$cid:$broadcastId"
                out.putIfAbsent(
                    key,
                    UnifiedSearchResult(
                        provider = ProviderId.ZATTOO,
                        ref = ProviderRef(ProviderId.ZATTOO, key, deepLinkHint = null, region = region),
                        title = title,
                        type = MediaType.LIVE_PROGRAM,
                        availabilityType = AvailabilityType.LIVE,
                    ),
                )
                if (out.size >= limit) return out.values.toList()
            }
        }
        return out.values.toList()
    }
}
