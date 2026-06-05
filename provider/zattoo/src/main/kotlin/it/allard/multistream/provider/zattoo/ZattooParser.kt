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
import kotlinx.serialization.json.JsonObject

/**
 * Parse Zattoo's power_guide response and keep programs whose title matches the query.
 * Power-guide program fields: t=title, s=start, e=end, id=broadcastId, i_url=still image.
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
                        ref = ProviderRef(ProviderId.ZATTOO, key, deepLinkHint = "https://zattoo.com/live/$cid", region = region),
                        title = title,
                        type = MediaType.LIVE_PROGRAM,
                        posterUrl = programImage(programObj),
                        availabilityType = AvailabilityType.LIVE,
                    ),
                )
                if (out.size >= limit) return out.values.toList()
            }
        }
        return out.values.toList()
    }

    /**
     * Program still image. Programs carry the full URL in `i_url` (relative path in `i`); upgrade it
     * to https since the API returns http, which Android blocks as cleartext.
     */
    private fun programImage(programObj: JsonObject): String? {
        val url = programObj["i_url"].string()?.takeIf { it.isNotBlank() }
            ?: programObj["i"].string()?.takeIf { it.isNotBlank() }?.let { "https://images.zattic.com/$it" }
        return url?.replace("http://", "https://")
    }
}
