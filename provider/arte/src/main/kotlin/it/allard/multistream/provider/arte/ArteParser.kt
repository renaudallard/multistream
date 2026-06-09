package it.allard.multistream.provider.arte

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.bool
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonObject

/**
 * Arte EMAC page parser. A video item carries a programId, title, a watch URL and a `kind`
 * (isCollection => series). The SEARCH page exposes its results in a single "listing" zone, while a
 * genre (category) page spreads programmes across many zones; the per-item mapping is shared.
 */
object ArteParser {
    /** Search page: the video results are the items of the "listing" zone. */
    fun parse(root: JsonObject, lang: String): List<UnifiedSearchResult> {
        val region = Region(lang.uppercase())
        val zones = root["zones"].array() ?: return emptyList()
        val listing = zones.firstOrNull { it.obj()?.get("code").string()?.startsWith("listing") == true }?.obj()
        val data = listing?.get("content").obj()?.get("data").array() ?: return emptyList()
        return data.mapNotNull { toResult(it.obj(), lang, region) }
    }

    /**
     * Genre (category) page: there is no single "listing" zone, so programmes are merged from every
     * zone's content.data, keeping only items that carry a programId (this skips the external web-link
     * teasers, banners and newsletter blocks, which have none) and de-duplicating by programId.
     */
    fun parseGenrePage(root: JsonObject, lang: String): List<UnifiedSearchResult> {
        val region = Region(lang.uppercase())
        val zones = root["zones"].array() ?: return emptyList()
        val out = LinkedHashMap<String, UnifiedSearchResult>()
        for (zone in zones) {
            val data = zone.obj()?.get("content").obj()?.get("data").array() ?: continue
            for (item in data) {
                val o = item.obj() ?: continue
                val programId = o["programId"].string() ?: continue
                if (out.containsKey(programId)) continue
                toResult(o, lang, region)?.let { out[programId] = it }
            }
        }
        return out.values.toList()
    }

    private fun toResult(o: JsonObject?, lang: String, region: Region): UnifiedSearchResult? {
        if (o == null) return null
        val title = o["title"].string()?.takeIf { it.isNotBlank() } ?: return null
        val id = o["programId"].string() ?: o["id"].string() ?: return null
        val url = o["url"].string() ?: "https://www.arte.tv/$lang/videos/$id/"
        val isCollection = o["kind"].obj()?.get("isCollection").bool() == true
        // mainImage.url carries a __SIZE__ placeholder to fill with a WxH.
        val poster = o["mainImage"].obj()?.get("url").string()?.replace("__SIZE__", "400x225")
        return UnifiedSearchResult(
            provider = ProviderId.ARTE,
            ref = ProviderRef(ProviderId.ARTE, id, url, region),
            title = title,
            type = if (isCollection) MediaType.SERIES else MediaType.MOVIE,
            posterUrl = poster,
            synopsis = o["shortDescription"].string()?.takeIf { it.isNotBlank() }
                ?: o["teaserText"].string()?.takeIf { it.isNotBlank() },
            availabilityType = AvailabilityType.FREE_ADS,
        )
    }
}
