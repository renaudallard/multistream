package it.allard.multistream.core.model

import java.text.Normalizer
import kotlin.math.abs

// Leading articles dropped before matching. Single-letter ones (en "a", fr "l", it "i") are left out
// on purpose: they collide with the English pronoun "I" and "A."/"L." initials, mangling titles like
// "I, Robot" or "L.A. Confidential".
private val ARTICLES = setOf(
    "the", "an", // en
    "le", "la", "les", "un", "une", "des", // fr
    "der", "die", "das", "ein", "eine", // de
    "il", "lo", "gli", "uno", "una", // it
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
    // A title made only of punctuation or non-Latin script normalizes to nothing; key it by a stable
    // digest of the raw text so two such titles get distinct TitleKeys instead of colliding.
    if (collapsed.isEmpty()) {
        val trimmed = raw.trim()
        return if (trimmed.isEmpty()) "" else "u" + Integer.toHexString(trimmed.lowercase().hashCode())
    }
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

    // Rows carrying any external id are authoritative: union those that share ANY id (imdb, tmdbTv or
    // tmdbMovie) so a row known only by a secondary id still merges with one that also carries imdb.
    val (external, heuristic) = results.partition { hasExternalId(it.externalIds) }
    groupByExternalIds(external).forEach { out += toTitle(it, providerPriority) }

    // normalizeTitle keys punctuation/non-Latin-only titles by a stable per-title digest, so distinct
    // such works land in distinct groups (and identical ones still merge) without a special case here.
    heuristic.groupBy { it.type to normalizeTitle(it.title) }
        .forEach { (_, members) -> clusterByYear(members).forEach { out += toTitle(it, providerPriority) } }

    return out
}

private fun hasExternalId(ids: ExternalIds): Boolean =
    ids.imdb != null || ids.tmdbTv != null || ids.tmdbMovie != null

private fun idTokens(ids: ExternalIds): List<String> = buildList {
    ids.imdb?.let { add("imdb:$it") }
    ids.tmdbTv?.let { add("tmdbtv:$it") }
    ids.tmdbMovie?.let { add("tmdbmovie:$it") }
}

/** Group rows into connected components where an edge is any shared external id (union-find). */
private fun groupByExternalIds(rows: List<UnifiedSearchResult>): List<List<UnifiedSearchResult>> {
    val parent = IntArray(rows.size) { it }
    fun find(x: Int): Int {
        var root = x
        while (parent[root] != root) {
            parent[root] = parent[parent[root]]
            root = parent[root]
        }
        return root
    }
    fun union(a: Int, b: Int) {
        parent[find(a)] = find(b)
    }
    val tokenOwner = HashMap<String, Int>()
    rows.forEachIndexed { i, r ->
        idTokens(r.externalIds).forEach { token ->
            tokenOwner.put(token, i)?.let { union(it, i) }
        }
    }
    return rows.indices.groupBy { find(it) }.values.map { idxs -> idxs.map { rows[it] } }
}

/**
 * Order merged titles by how well they match the query. An exact normalized match ranks first, then
 * a title that starts with the query, then one that contains the whole query phrase, then titles
 * holding all query words, then partial-word matches. A title offered by more providers breaks ties.
 * Ties otherwise keep merge order. This surfaces "Police Academy" (from any service) above the
 * "police ..." partial matches.
 */
fun rankByRelevance(query: String, titles: List<Title>): List<Title> {
    val q = normalizeTitle(query)
    if (q.isBlank()) return titles
    val words = q.split(' ').filter { it.isNotEmpty() }
    return titles.sortedByDescending { relevanceScore(q, words, normalizeTitle(it.primaryTitle), it.availabilities.size) }
}

private fun relevanceScore(query: String, words: List<String>, title: String, providers: Int): Int {
    val titleWords = title.split(' ').filter { it.isNotEmpty() }
    fun matches(queryWord: String) = titleWords.any { it.contains(queryWord) || fuzzyMatch(queryWord, it) }
    val base = when {
        title == query -> 1000
        title.startsWith(query) -> 800
        title.contains(query) -> 600
        words.isNotEmpty() && words.all { matches(it) } -> 400
        else -> words.count { matches(it) } * 200 / words.size.coerceAtLeast(1)
    }
    return base + providers.coerceAtMost(9)
}

/** True when [word] is within a small edit distance of [candidate] (tolerates query typos). */
private fun fuzzyMatch(word: String, candidate: String): Boolean {
    if (word.length < 4) return false
    val tolerance = word.length / 4 + 1
    if (abs(word.length - candidate.length) > tolerance) return false
    return levenshtein(word, candidate) <= tolerance
}

private fun levenshtein(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val curr = IntArray(b.length + 1)
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
        }
        prev = curr
    }
    return prev[b.length]
}

/** Split rows that share (type, normTitle) into clusters spanning at most 1 year from their anchor. */
private fun clusterByYear(members: List<UnifiedSearchResult>): List<List<UnifiedSearchResult>> {
    val withYear = members.filter { it.year != null }.sortedBy { it.year }
    val noYear = members.filter { it.year == null }
    val clusters = mutableListOf<MutableList<UnifiedSearchResult>>()
    for (r in withYear) {
        // Compare to the cluster's first (earliest) year, not the last added one: comparing to the
        // last member would let 2019->2020->2021 chain into one cluster spanning two years.
        val current = clusters.lastOrNull()
        if (current != null && abs(r.year!! - current.first().year!!) <= 1) current.add(r)
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
        synopsis = ordered.firstNotNullOfOrNull { it.synopsis },
        externalIds = external,
        availabilities = availabilities,
    )
}

/**
 * Merge episode lists fetched from several providers into one complete run: episodes are unioned by
 * (season, episode) number, so a provider with the full series fills the gaps of one that only carries
 * part of it. The first non-null title/synopsis/runtime/still wins and each episode's provider refs
 * accumulate. Seasons and episodes come back sorted.
 */
fun mergeSeasons(perProvider: List<List<Season>>): List<Season> {
    val merged = LinkedHashMap<Pair<Int, Int>, Episode>()
    val seasonTitles = LinkedHashMap<Int, String>()
    for (season in perProvider.flatten()) {
        season.title?.let { seasonTitles.putIfAbsent(season.seasonNumber, it) }
        for (ep in season.episodes) {
            val key = ep.seasonNumber to ep.episodeNumber
            val existing = merged[key]
            merged[key] = if (existing == null) ep else existing.copy(
                title = existing.title ?: ep.title,
                synopsis = existing.synopsis ?: ep.synopsis,
                runtimeMin = existing.runtimeMin ?: ep.runtimeMin,
                stillUrl = existing.stillUrl ?: ep.stillUrl,
                providerRefs = existing.providerRefs + ep.providerRefs,
            )
        }
    }
    return merged.values
        .groupBy { it.seasonNumber }
        .toList()
        .sortedBy { it.first }
        .map { (number, eps) -> Season(number, seasonTitles[number], eps.sortedBy { it.episodeNumber }) }
}
