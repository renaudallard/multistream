package it.allard.multistream.provider.toutv

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.ProviderTitleDetails
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import kotlinx.serialization.json.JsonObject

/**
 * Parser for ICI Tou.tv's Radio-Canada OTT catalog API. The search endpoint returns Show-level cards
 * (no episodes and no structured type); the show-detail endpoint carries the authoritative media type,
 * release year, synopsis and cast.
 */
object ToutvParser {
    private const val IMAGE_WIDTH = "360"

    fun parseSearch(root: JsonObject): List<UnifiedSearchResult> {
        val results = root["results"].array() ?: return emptyList()
        return results.mapNotNull { item ->
            val o = item.obj() ?: return@mapNotNull null
            // "Section" results are curated rows or live channels, not a watchable title; keep shows.
            if (o["type"].string() != "Show") return@mapNotNull null
            val title = o["title"].string()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val slug = o["url"].string()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Search carries no structured type; a "... saison(s)" subtitle marks a series, else a film.
            // getDetails corrects this from the show's schema.org @type when the title is opened.
            val series = o["infoTitle"].string()?.contains("saison", ignoreCase = true) == true
            UnifiedSearchResult(
                provider = ProviderId.TOUTV,
                ref = ProviderRef(ProviderId.TOUTV, slug, "https://ici.tou.tv/$slug", Region("CA")),
                title = title,
                type = if (series) MediaType.SERIES else MediaType.MOVIE,
                posterUrl = cardImage(o),
                availabilityType = AvailabilityType.SUBSCRIPTION,
            )
        }
    }

    fun parseDetails(root: JsonObject, ref: ProviderRef): ProviderTitleDetails? {
        val meta = root["structuredMetadata"].obj()
        // schema.org @type is the reliable movie-vs-series discriminator ("Movie" vs "TVSeries").
        val type = if (meta?.get("@type").string() == "Movie") MediaType.MOVIE else MediaType.SERIES
        val year = (meta?.get("datePublished").string() ?: meta?.get("startDate").string())
            ?.take(4)?.toIntOrNull()
        val cast = meta?.get("actor").array()
            ?.mapNotNull { it.obj()?.get("name").string()?.takeIf { name -> name.isNotBlank() } }
            ?.take(15).orEmpty()
        return ProviderTitleDetails(
            ref = ref,
            title = root["title"].string()?.takeIf { it.isNotBlank() } ?: ref.providerTitleId,
            type = type,
            year = year,
            synopsis = root["description"].string()?.takeIf { it.isNotBlank() }
                ?: meta?.get("abstract").string()?.takeIf { it.isNotBlank() },
            posterUrl = cardImage(root),
            cast = cast,
        )
    }

    /** The card image URL with the literal "(_Size_)" placeholder filled with a pixel width. */
    private fun cardImage(node: JsonObject): String? =
        node["images"].obj()?.get("card").obj()?.get("url").string()?.replace("(_Size_)", IMAGE_WIDTH)
}
