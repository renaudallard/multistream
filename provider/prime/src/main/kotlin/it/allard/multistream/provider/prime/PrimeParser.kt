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

/**
 * Extract Prime Video search results from the embedded `text/template` JSON. Tolerant: any object
 * carrying a title plus a title identifier (gti/titleID/asin) is treated as a result.
 */
object PrimeParser {
    private val TEMPLATE = Regex("<script[^>]*type=\"text/template\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
    private val ID_KEYS = listOf("titleID", "titleId", "gti", "asin")

    fun parse(html: String, region: Region): List<UnifiedSearchResult> {
        val out = LinkedHashMap<String, UnifiedSearchResult>()
        for (match in TEMPLATE.findAll(html)) {
            val json = runCatching { NetJson.parseToJsonElement(match.groupValues[1]) }.getOrNull() ?: continue
            collect(json, region, out)
        }
        return out.values.toList()
    }

    private fun collect(element: JsonElement, region: Region, out: MutableMap<String, UnifiedSearchResult>) {
        when (element) {
            is JsonObject -> {
                val title = element["title"].string()
                val id = ID_KEYS.firstNotNullOfOrNull { element[it].string() }
                if (!title.isNullOrBlank() && id != null) {
                    val media = if (element["contentType"].string()?.uppercase() == "MOVIE") MediaType.MOVIE else MediaType.SERIES
                    out.putIfAbsent(
                        id,
                        UnifiedSearchResult(
                            provider = ProviderId.PRIME,
                            ref = ProviderRef(ProviderId.PRIME, id, "https://app.primevideo.com/detail?gti=$id", region),
                            title = title,
                            type = media,
                            availabilityType = AvailabilityType.SUBSCRIPTION,
                        ),
                    )
                }
                element.values.forEach { collect(it, region, out) }
            }
            is JsonArray -> element.forEach { collect(it, region, out) }
            else -> Unit
        }
    }
}
