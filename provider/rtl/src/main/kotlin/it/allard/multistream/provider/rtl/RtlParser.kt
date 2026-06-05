package it.allard.multistream.provider.rtl

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.Episode
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.int
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

    /**
     * Parse a detail3 page into title details: synopsis (`description`), the production year (the
     * `headerLabels` item tagged "Année de production") and the cast (the `moreInfo.meta` "Rôles" row).
     */
    fun parseDetails(root: JsonObject, ref: ProviderRef): ProviderTitleDetails {
        val year = root["headerLabels"].array()
            ?.firstOrNull { it.obj()?.get("accessibilityLabel").string() == "Année de production" }
            ?.obj()?.get("label").int()
        val cast = root["moreInfo"].obj()?.get("meta").array()
            ?.firstOrNull { it.obj()?.get("label").string() == "Rôles" }
            ?.obj()?.get("items").array()?.mapNotNull { it.obj()?.get("label").string() }.orEmpty()
        return ProviderTitleDetails(
            ref = ref,
            title = root["title"].obj()?.get("label").string() ?: "",
            type = MediaType.SERIES,
            year = year,
            synopsis = root["description"].string()?.takeIf { it.isNotBlank() },
            cast = cast,
        )
    }

    /** Season indices available on a detail3 page (`seasonPicker.indices`, e.g. [3] or [7,8,9]). */
    fun seasonIndices(root: JsonObject): List<Int> =
        root["seasonPicker"].obj()?.get("indices").array()?.mapNotNull { it.int() }.orEmpty()

    private val EPISODE_NUMBER_PREFIX = Regex("^\\d+\\.\\s*")

    /** The currently-selected season of a detail3 page (`seasonPicker.selected` + its episodes). */
    fun parseSeason(root: JsonObject): Season? {
        val selected = root["seasonPicker"].obj()?.get("selected").obj() ?: return null
        val index = selected["index"].int() ?: return null
        val episodes = selected["episodes"].array()?.mapNotNull { item ->
            val o = item.obj() ?: return@mapNotNull null
            val number = o["index"].int() ?: return@mapNotNull null
            Episode(
                seasonNumber = index,
                episodeNumber = number,
                // Titles are prefixed with the episode number ("1. ..."); drop it.
                title = o["title"].string()?.replace(EPISODE_NUMBER_PREFIX, "")?.takeIf { it.isNotBlank() },
                synopsis = o["description"].string()?.takeIf { it.isNotBlank() },
                runtimeMin = o["durationSeconds"].int()?.let { it / 60 },
            )
        } ?: emptyList()
        return Season(index, null, episodes)
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
