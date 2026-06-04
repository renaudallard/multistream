package it.allard.multistream.provider.rts

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * Parse an SRG Integration Layer `searchResultMediaList`. Each media carries a `urn`
 * (urn:rts:video:<id>), a title and an imageUrl. Only VIDEO items are kept; the numeric id from the
 * urn yields the Play RTS deep link `www.rts.ch/play/tv/redirect/detail/<id>`.
 */
object RtsParser {
    fun parse(root: JsonObject): List<UnifiedSearchResult> {
        val list = root["searchResultMediaList"].array() ?: return emptyList()
        val out = LinkedHashMap<String, UnifiedSearchResult>()
        for (item in list) {
            val o = item.obj() ?: continue
            if (o["mediaType"].string() != "VIDEO") continue
            val urn = o["urn"].string() ?: continue
            val title = o["title"].string()?.takeIf { it.isNotBlank() } ?: continue
            val id = urn.substringAfterLast(":")
            // Serve the poster through the IL image proxy (il.srgssr.ch) rather than the raw img.rts.ch
            // URL: some networks fail to resolve rts.ch image subdomains while il.srgssr.ch (used by
            // search) resolves, and the proxy also scales the image.
            val poster = o["imageUrl"].string()?.let {
                "https://il.srgssr.ch/images/?imageUrl=${URLEncoder.encode(it, "UTF-8")}&format=jpg&width=480"
            }
            out.putIfAbsent(
                urn,
                UnifiedSearchResult(
                    provider = ProviderId.RTS,
                    ref = ProviderRef(ProviderId.RTS, urn, "https://www.rts.ch/play/tv/redirect/detail/$id", Region("CH")),
                    title = title,
                    type = MediaType.MOVIE,
                    posterUrl = poster,
                    availabilityType = AvailabilityType.FREE_ADS,
                ),
            )
        }
        return out.values.toList()
    }
}
