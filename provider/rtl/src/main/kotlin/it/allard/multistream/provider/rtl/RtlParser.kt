package it.allard.multistream.provider.rtl

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
import java.text.Normalizer

/**
 * Parse RTL Play lfvp search responses. The body is `results[]` sections (grouped by relevance), each
 * holding `teasers[]` items with a title, a `detailId` (the catalog id) and an absolute image URL.
 * Sections are flattened into one result list; the deep link is `rtlplay.be/rtlplay/<slug>~<detailId>`.
 */
object RtlParser {
    fun parse(root: JsonObject, region: Region): List<UnifiedSearchResult> {
        val sections = root["results"].array() ?: return emptyList()
        val out = LinkedHashMap<String, UnifiedSearchResult>()
        for (section in sections) {
            val teasers = section.obj()?.get("teasers").array() ?: continue
            for (teaser in teasers) {
                val o = teaser.obj() ?: continue
                val title = o["title"].string()?.takeIf { it.isNotBlank() } ?: continue
                val id = o["detailId"].string() ?: continue
                val deepLink = "https://www.rtlplay.be/rtlplay/${slug(title)}~$id"
                out.putIfAbsent(
                    id,
                    UnifiedSearchResult(
                        provider = ProviderId.RTL,
                        ref = ProviderRef(ProviderId.RTL, id, deepLink, region),
                        title = title,
                        type = MediaType.SERIES,
                        posterUrl = o["imageUrl"].string() ?: o["overlayImageUrl"].string(),
                        availabilityType = AvailabilityType.FREE_ADS,
                    ),
                )
            }
        }
        return out.values.toList()
    }

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val APOSTROPHES = Regex("['’]")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /**
     * Build the rtlplay.be url slug from a title, matching the site's form: lowercase, drop
     * apostrophes (d'urgence -> durgence), strip accents, and hyphenate the rest.
     */
    private fun slug(title: String): String {
        val noApostrophes = title.lowercase().replace(APOSTROPHES, "")
        val noDiacritics = Normalizer.normalize(noApostrophes, Normalizer.Form.NFD).replace(DIACRITICS, "")
        return noDiacritics.replace(NON_ALNUM, "-").trim('-')
    }
}
