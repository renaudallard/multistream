package it.allard.multistream.core.model

import kotlinx.serialization.Serializable

/** The services this app federates. */
enum class ProviderId { NETFLIX, DISNEY, PRIME, MOLOTOV, ZATTOO, ARTE, PLEX, RTBF, RTL, RTS }

enum class MediaType { MOVIE, SERIES, EPISODE, LIVE_CHANNEL, LIVE_PROGRAM }

enum class AvailabilityType { SUBSCRIPTION, LIVE, CATCHUP, RENT, BUY, FREE_ADS, UNKNOWN }

/** ISO 3166-1 alpha-2 region, configured per provider. */
@Serializable
data class Region(val code: String) {
    companion object {
        val FR = Region("FR")
        val CH = Region("CH")
        val DE = Region("DE")
        val IT = Region("IT")
    }
}

/** Cross-service identifiers used to reconcile the same work across providers. */
@Serializable
data class ExternalIds(
    val imdb: String? = null,
    val tmdbMovie: Long? = null,
    val tmdbTv: Long? = null,
) {
    val isEmpty: Boolean get() = imdb == null && tmdbMovie == null && tmdbTv == null
}

/** A pointer to one title as it exists on a single provider; the deep-link primitive. */
@Serializable
data class ProviderRef(
    val provider: ProviderId,
    val providerTitleId: String,
    val deepLinkHint: String? = null,
    val region: Region? = null,
)

/** One provider that offers a title, with how (subscription / live / replay) and until when. */
@Serializable
data class Availability(
    val provider: ProviderId,
    val ref: ProviderRef,
    val type: AvailabilityType = AvailabilityType.UNKNOWN,
    val region: Region? = null,
    val expiresAt: Long? = null,
)

/** One row returned from a provider search, before cross-provider merge. */
@Serializable
data class UnifiedSearchResult(
    val provider: ProviderId,
    val ref: ProviderRef,
    val title: String,
    val type: MediaType,
    val year: Int? = null,
    val posterUrl: String? = null,
    val availabilityType: AvailabilityType = AvailabilityType.UNKNOWN,
    val externalIds: ExternalIds = ExternalIds(),
)

@Serializable
data class Episode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val synopsis: String? = null,
    val runtimeMin: Int? = null,
    val stillUrl: String? = null,
    val providerRefs: List<ProviderRef> = emptyList(),
)

@Serializable
data class Season(
    val seasonNumber: Int,
    val title: String? = null,
    val episodes: List<Episode> = emptyList(),
)

/** A merged title shown in the UI: the same work, possibly available on several providers. */
@Serializable
data class Title(
    val key: TitleKey,
    val primaryTitle: String,
    val type: MediaType,
    val year: Int? = null,
    val synopsis: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val cast: List<String> = emptyList(),
    val externalIds: ExternalIds = ExternalIds(),
    val availabilities: List<Availability> = emptyList(),
    val seasons: List<Season> = emptyList(),
)

/** Provider-native detail result, mapped into [Title] by the merge layer. */
@Serializable
data class ProviderTitleDetails(
    val ref: ProviderRef,
    val title: String,
    val type: MediaType,
    val year: Int? = null,
    val synopsis: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val cast: List<String> = emptyList(),
    val externalIds: ExternalIds = ExternalIds(),
    val seasons: List<Season> = emptyList(),
)

/** A season+episode coordinate, ordered first by season then episode. */
@Serializable
data class EpisodeCoord(val season: Int, val episode: Int) : Comparable<EpisodeCoord> {
    override fun compareTo(other: EpisodeCoord): Int =
        compareValuesBy(this, other, { it.season }, { it.episode })
}
