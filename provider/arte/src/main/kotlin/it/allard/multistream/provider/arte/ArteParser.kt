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
 * Arte SEARCH page parser. The video results are the items of the "listing" zone; each carries a
 * programId, title, a watch URL and a `kind` (isCollection => series). Other zones (the boutique/shop
 * and recommendation rows) are ignored.
 */
object ArteParser {
    fun parse(root: JsonObject, lang: String): List<UnifiedSearchResult> {
        val region = Region(lang.uppercase())
        val zones = root["zones"].array() ?: return emptyList()
        val listing = zones.firstOrNull { it.obj()?.get("code").string()?.startsWith("listing") == true }?.obj()
        val data = listing?.get("content").obj()?.get("data").array() ?: return emptyList()
        return data.mapNotNull { item ->
            val o = item.obj() ?: return@mapNotNull null
            val title = o["title"].string()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val id = o["programId"].string() ?: o["id"].string() ?: return@mapNotNull null
            val url = o["url"].string() ?: "https://www.arte.tv/$lang/videos/$id/"
            val isCollection = o["kind"].obj()?.get("isCollection").bool() == true
            // mainImage.url carries a __SIZE__ placeholder to fill with a WxH.
            val poster = o["mainImage"].obj()?.get("url").string()?.replace("__SIZE__", "400x225")
            UnifiedSearchResult(
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
}
