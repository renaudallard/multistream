package it.allard.multistream.core.model

import kotlinx.serialization.Serializable

/**
 * Stable, provider-independent identity for a work. Local watch tracking keys on this so
 * progress survives even when a title appears/disappears from a given provider.
 *
 * Canonical string form (used as the Room primary key):
 *  - External:  "ext:<ns>:<id>"     e.g. "ext:imdb:tt0903747"
 *  - Heuristic: "h:<normTitle>:<year?>"  e.g. "h:mandalorian:2019"
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
    data class Heuristic(val normTitle: String, val year: Int?) : TitleKey {
        override fun serialize(): String = "h:$normTitle:${year ?: ""}"
    }

    companion object {
        fun parse(s: String): TitleKey = when {
            s.startsWith("ext:") -> {
                val rest = s.removePrefix("ext:")
                val sep = rest.indexOf(':')
                if (sep < 0) Heuristic(rest, null)
                else External(rest.substring(0, sep), rest.substring(sep + 1))
            }
            s.startsWith("h:") -> {
                val rest = s.removePrefix("h:")
                val sep = rest.lastIndexOf(':')
                if (sep < 0) Heuristic(rest, null)
                else Heuristic(rest.substring(0, sep), rest.substring(sep + 1).toIntOrNull())
            }
            else -> Heuristic(s, null)
        }
    }
}
