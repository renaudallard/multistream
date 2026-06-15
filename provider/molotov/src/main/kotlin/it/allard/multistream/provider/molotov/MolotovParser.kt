package it.allard.multistream.provider.molotov

import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.Episode
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.net.array
import it.allard.multistream.core.net.obj
import it.allard.multistream.core.net.string
import it.allard.multistream.provider.api.DeepLinks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLDecoder

/**
 * Walks a Fubo server-driven page (`papi/v1/...`) and maps its content cards to the unified model.
 * Every deep-linkable card carries its target in an `actions.on_click[].endpoint.url` of the form
 * `program-details/{series|program|channel}/{id}`; that (kind, id) is exactly what an
 * `etincelle://{kind}/{id}` deep link needs, so one set of regexes drives both the typing and the
 * launch link. Mirrors etincelle's PageDtos mapping.
 */
object MolotovParser {
    private val CHANNEL = Regex("""program-details/channel/(\d+)""")
    private val PROGRAM = Regex("""program-details/program/([\w-]+)""")
    private val SERIES = Regex("""program-details/series/([\w-]+)""")
    private val TRK_TITLE = Regex("""[?&]trkOriginElement=([^&]+)""")

    // Cap recursion so a deeply nested (or hostile) response can't overflow the stack.
    private const val MAX_DEPTH = 100

    fun parsePage(root: JsonElement, region: Region): List<UnifiedSearchResult> {
        val cards = mutableListOf<JsonObject>()
        collectCards(root, cards)
        val seen = HashSet<String>()
        // The same show appears once per channel it airs on; keep one card per title.
        return cards.mapNotNull { toResult(it, region) }
            .filter { seen.add(it.title.trim().lowercase()) }
    }

    private fun collectCards(element: JsonElement, out: MutableList<JsonObject>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonArray -> element.forEach { collectCards(it, out, depth + 1) }
            is JsonObject -> {
                if (deepLinkUrl(element) != null) out.add(element)
                element.values.forEach { collectCards(it, out, depth + 1) }
            }
            else -> Unit
        }
    }

    /** The card's first on_click endpoint url, but only when it points at a deep-linkable detail. */
    private fun deepLinkUrl(card: JsonObject): String? {
        val url = card["actions"].obj()?.get("on_click").array()
            ?.firstNotNullOfOrNull { it.obj()?.get("endpoint").obj()?.get("url").string() }
            ?: return null
        return url.takeIf { CHANNEL.containsMatchIn(it) || PROGRAM.containsMatchIn(it) || SERIES.containsMatchIn(it) }
    }

    private fun toResult(card: JsonObject, region: Region): UnifiedSearchResult? {
        val url = deepLinkUrl(card) ?: return null
        // A channel is live TV; a series lists episodes; a program is a single playable.
        val (kind, id, media) = when {
            CHANNEL.find(url) != null -> Triple("channel", CHANNEL.find(url)!!.groupValues[1], MediaType.LIVE_CHANNEL)
            SERIES.find(url) != null -> Triple("series", SERIES.find(url)!!.groupValues[1], MediaType.SERIES)
            PROGRAM.find(url) != null -> Triple("program", PROGRAM.find(url)!!.groupValues[1], MediaType.MOVIE)
            else -> return null
        }
        val title = cardTitle(card, url) ?: return null
        return UnifiedSearchResult(
            provider = ProviderId.MOLOTOV,
            ref = ProviderRef(
                provider = ProviderId.MOLOTOV,
                providerTitleId = "$kind:$id",
                deepLinkHint = DeepLinks.etincelle(kind, id),
                region = region,
            ),
            title = title,
            type = media,
            posterUrl = cardImage(card),
            availabilityType = if (media == MediaType.LIVE_CHANNEL) AvailabilityType.LIVE else AvailabilityType.SUBSCRIPTION,
        )
    }

    // --- Episodes (a series' "Regarder maintenant" / catch-up tab) ---

    private const val LIST_ITEM_WIDE = "list-item-wide"
    // Episode labels read e.g. "S1 E5 - Titre", "S01E05", or "Saison 1 Épisode 5"; pull the coordinates
    // from whichever shape is present, falling back to list order when the label carries no number.
    private val SEASON_EPISODE = Regex("""S\s*(\d+)\s*[\s.\-·]*E\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val SAISON_EPISODE = Regex("""saison\s*(\d+).*?[ée]pisode\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val EPISODE_ONLY = Regex("""[ée]pisode\s*(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Episodes from a series' catch-up tab. Only `list-item-wide` sections are episode lists, so the
     * detail page's recommendation carousels ("À voir aussi") are skipped. Mirrors etincelle's
     * `toEpisodes()`, but recovers the season/episode coordinates the unified model needs.
     */
    fun parseSeasons(root: JsonElement): List<Season> {
        val tiles = mutableListOf<JsonObject>()
        collectEpisodeTiles(root, tiles)
        var running = 0
        val episodes = tiles.mapNotNull { toEpisode(it) { ++running } }
            .distinctBy { it.seasonNumber to it.episodeNumber }
        return episodes.groupBy { it.seasonNumber }.toSortedMap().map { (number, list) ->
            Season(seasonNumber = number, episodes = list.sortedBy { it.episodeNumber })
        }
    }

    private fun collectEpisodeTiles(element: JsonElement, out: MutableList<JsonObject>, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        when (element) {
            is JsonArray -> element.forEach { collectEpisodeTiles(it, out, depth + 1) }
            is JsonObject -> {
                if (element["component_type"].string() == LIST_ITEM_WIDE) {
                    element["components"].array()?.forEach { card -> card.obj()?.let(out::add) }
                }
                element.values.forEach { collectEpisodeTiles(it, out, depth + 1) }
            }
            else -> Unit
        }
    }

    private fun toEpisode(tile: JsonObject, nextEpisode: () -> Int): Episode? {
        val label = textOf(tile["title"]) ?: textOf(tile["heading"]) ?: textOf(tile["footer"].obj()?.get("title"))
        // The coordinates usually ride in a subtitle (real shape: footer.subtitle = "S8 E24 <name>").
        val subtitle = textOf(tile["subtitle"]) ?: textOf(tile["footer"].obj()?.get("subtitle"))
        if (label == null && subtitle == null) return null
        val coded = listOfNotNull(label, subtitle).joinToString(" ")
        val match = SEASON_EPISODE.find(coded) ?: SAISON_EPISODE.find(coded)
        val (season, episode) = if (match != null) {
            (match.groupValues[1].toIntOrNull() ?: 1) to (match.groupValues[2].toIntOrNull() ?: nextEpisode())
        } else {
            1 to (EPISODE_ONLY.find(coded)?.groupValues?.get(1)?.toIntOrNull() ?: nextEpisode())
        }
        return Episode(
            seasonNumber = season,
            episodeNumber = episode,
            title = episodeTitle(label, subtitle),
            stillUrl = cardImage(tile),
        )
    }

    /**
     * The episode's own name: the text after the "Sx Ey" coordinates in whichever field carries them
     * (the real shape is "S8 E24 La détermination", no separator); otherwise the label as-is.
     */
    private fun episodeTitle(label: String?, subtitle: String?): String? {
        for (text in listOfNotNull(subtitle, label)) {
            val match = SEASON_EPISODE.find(text) ?: SAISON_EPISODE.find(text) ?: continue
            text.substring(match.range.last + 1).trim(' ', '-', '·', ':', '.')
                .takeIf { it.isNotBlank() }?.let { return it }
        }
        val base = label ?: return null
        return base.substringAfter(" - ", base).trim().takeIf { it.isNotBlank() }
    }

    // --- shared card helpers ---

    // Live cards put the show title in a footer; poster cards carry none, only a tracking param.
    private fun cardTitle(card: JsonObject, url: String): String? {
        val text = textOf(card["title"]) ?: textOf(card["heading"])
            ?: textOf(card["footer"].obj()?.get("title"))
            ?: trkTitle(url)
        return text?.takeIf { it.isNotBlank() }
    }

    private fun cardImage(card: JsonObject): String? =
        urlOf(card["picture"])
            ?: urlOf(card["body"].obj()?.get("picture"))
            ?: urlOf(card["image"])
            ?: urlOf(card["image_compact"])

    private fun textOf(element: JsonElement?): String? = element.obj()?.get("text").string()

    private fun urlOf(element: JsonElement?): String? = element.obj()?.get("url").string()

    private fun trkTitle(url: String): String? =
        TRK_TITLE.find(url)?.groupValues?.get(1)?.let {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
        }?.takeIf { it.isNotBlank() }
}
