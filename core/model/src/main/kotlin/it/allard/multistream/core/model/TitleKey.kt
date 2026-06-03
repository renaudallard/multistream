package it.allard.multistream.core.model

import kotlinx.serialization.Serializable

/**
 * Stable, provider-independent identity for a work. Local watch tracking keys on this so
 * progress survives even when a title appears/disappears from a given provider.
 *
 * Canonical string form (used as the Room primary key):
 *  - External:  "ext:<ns>:<id>"     e.g. "ext:imdb:tt0903747"
 *  - Heuristic: "h:<type>:<normTitle>:<year?>"  e.g. "h:series:mandalorian:2019"
 *
 * [normTitle] is always normalized (lowercase, no diacritics/punctuation) so ':' never appears in it.
 */
@Serializable
sealed interface TitleKey {
    fun serialize(): String

    @Serializable
    data class External(val ns: String, val id: String) : TitleKey {
        override fun serialize(): String = "ext:$ns:$id"
    }

    @Serializable
    data class Heuristic(val type: MediaType, val normTitle: String, val year: Int?) : TitleKey {
        override fun serialize(): String = "h:${type.name.lowercase()}:$normTitle:${year ?: ""}"
    }

    companion object {
        fun parse(s: String): TitleKey = when {
            s.startsWith("ext:") -> {
                val rest = s.removePrefix("ext:")
                val sep = rest.indexOf(':')
                if (sep < 0) Heuristic(MediaType.SERIES, rest, null)
                else External(rest.substring(0, sep), rest.substring(sep + 1))
            }
            s.startsWith("h:") -> {
                val rest = s.removePrefix("h:")
                val firstSep = rest.indexOf(':')
                val lastSep = rest.lastIndexOf(':')
                if (firstSep < 0 || firstSep == lastSep) {
                    Heuristic(MediaType.SERIES, rest, null)
                } else {
                    val type = runCatching { MediaType.valueOf(rest.substring(0, firstSep).uppercase()) }
                        .getOrDefault(MediaType.SERIES)
                    Heuristic(type, rest.substring(firstSep + 1, lastSep), rest.substring(lastSep + 1).toIntOrNull())
                }
            }
            else -> Heuristic(MediaType.SERIES, s, null)
        }
    }
}
