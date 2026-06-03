package it.allard.multistream.core.model

/**
 * Compute the next episode to watch in a series.
 *
 * @param lastWatched the highest episode the user has marked watched, or null if none.
 * @param seasons the series' season/episode list (from the best detail provider).
 * @return the next episode coordinate, or null if the finale is watched or there is nothing to watch.
 */
fun computeNextEpisode(lastWatched: EpisodeCoord?, seasons: List<Season>): EpisodeCoord? {
    val ordered = seasons
        .sortedBy { it.seasonNumber }
        .flatMap { season ->
            season.episodes
                .sortedBy { it.episodeNumber }
                .map { EpisodeCoord(season.seasonNumber, it.episodeNumber) }
        }
    if (ordered.isEmpty()) return null
    if (lastWatched == null) return ordered.first()

    val idx = ordered.indexOf(lastWatched)
    return when {
        idx < 0 -> ordered.firstOrNull { it > lastWatched } // stale pointer: next known greater
        idx == ordered.lastIndex -> null // finale watched
        else -> ordered[idx + 1]
    }
}
