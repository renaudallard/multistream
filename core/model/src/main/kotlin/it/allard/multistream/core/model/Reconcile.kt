package it.allard.multistream.core.model

import java.text.Normalizer
import kotlin.math.abs

private val ARTICLES = setOf(
    "the", "a", "an", // en
    "le", "la", "les", "l", "un", "une", "des", // fr
    "der", "die", "das", "ein", "eine", // de
    "il", "lo", "gli", "i", "uno", "una", // it
)

private val DIACRITICS = Regex("\\p{Mn}+")
private val NON_ALNUM = Regex("[^a-z0-9]+")
private val WHITESPACE = Regex("\\s+")

/**
 * Normalize a title for heuristic matching: lowercase, strip diacritics and punctuation,
 * collapse whitespace, drop a single leading article. Locale-independent.
 */
fun normalizeTitle(raw: String): String {
    val lower = raw.lowercase()
    val noDiacritics = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(DIACRITICS, "")
    val alnum = noDiacritics.replace(NON_ALNUM, " ").trim()
    val collapsed = alnum.replace(WHITESPACE, " ")
    val sep = collapsed.indexOf(' ')
    if (sep <= 0) return collapsed
    val first = collapsed.substring(0, sep)
    return if (first in ARTICLES) collapsed.substring(sep + 1) else collapsed
}

/** Authoritative key from external ids when present, else a normalized type+title+year heuristic. */
fun titleKeyFor(title: String, year: Int?, externalIds: ExternalIds, type: MediaType): TitleKey {
    externalIds.imdb?.let { return TitleKey.External("imdb", it) }
    externalIds.tmdbTv?.let { return TitleKey.External("tmdbtv", it.toString()) }
    externalIds.tmdbMovie?.let { return TitleKey.External("tmdbmovie", it.toString()) }
    return TitleKey.Heuristic(type, normalizeTitle(title), year)
}

private fun priorityIndex(p: ProviderId, priority: List<ProviderId>): Int =
    priority.indexOf(p).let { if (it < 0) Int.MAX_VALUE else it }

private fun mergeExternalIds(members: List<UnifiedSearchResult>): ExternalIds = ExternalIds(
    imdb = members.firstNotNullOfOrNull { it.externalIds.imdb },
    tmdbMovie = members.firstNotNullOfOrNull { it.externalIds.tmdbMovie },
    tmdbTv = members.firstNotNullOfOrNull { it.externalIds.tmdbTv },
)

/**
 * Merge per-provider search rows into unified titles: same work => one card with several
 * provider availabilities. External-id matches are authoritative; otherwise rows are grouped
 * by (media type, normalized title) and split into year clusters with a +/-1 tolerance so
 * remakes stay separate while release-vs-streaming-year skew still merges.
 */
fun mergeResults(
    results: List<UnifiedSearchResult>,
    providerPriority: List<ProviderId> = ProviderId.entries,
): List<Title> {
    val out = mutableListOf<Title>()

    val byExternal = LinkedHashMap<String, MutableList<UnifiedSearchResult>>()
    val heuristic = mutableListOf<UnifiedSearchResult>()
    for (r in results) {
        when (val k = titleKeyFor(r.title, r.year, r.externalIds, r.type)) {
            is TitleKey.External -> byExternal.getOrPut(k.serialize()) { mutableListOf() }.add(r)
            is TitleKey.Heuristic -> heuristic.add(r)
        }
    }
    byExternal.values.forEach { out += toTitle(it, providerPriority) }

    heuristic.groupBy { it.type to normalizeTitle(it.title) }
        .forEach { (_, members) -> clusterByYear(members).forEach { out += toTitle(it, providerPriority) } }

    return out
}

/** Split rows that share (type, normTitle) into clusters whose years are within 1 of each other. */
private fun clusterByYear(members: List<UnifiedSearchResult>): List<List<UnifiedSearchResult>> {
    val withYear = members.filter { it.year != null }.sortedBy { it.year }
    val noYear = members.filter { it.year == null }
    val clusters = mutableListOf<MutableList<UnifiedSearchResult>>()
    for (r in withYear) {
        val current = clusters.lastOrNull()
        if (current != null && abs(r.year!! - current.last().year!!) <= 1) current.add(r)
        else clusters.add(mutableListOf(r))
    }
    if (noYear.isNotEmpty()) {
        if (clusters.isEmpty()) clusters.add(noYear.toMutableList()) else clusters.first().addAll(noYear)
    }
    return clusters
}

private fun toTitle(members: List<UnifiedSearchResult>, priority: List<ProviderId>): Title {
    val ordered = members.sortedBy { priorityIndex(it.provider, priority) }
    val primary = ordered.first()
    val year = ordered.firstNotNullOfOrNull { it.year }
    val external = mergeExternalIds(members)
    val key = titleKeyFor(primary.title, year, external, primary.type)
    val availabilities = members
        .map { Availability(it.provider, it.ref, it.availabilityType, it.ref.region) }
        .distinctBy { it.provider }
    return Title(
        key = key,
        primaryTitle = primary.title,
        type = primary.type,
        year = year,
        posterUrl = ordered.firstNotNullOfOrNull { it.posterUrl },
        externalIds = external,
        availabilities = availabilities,
    )
}
