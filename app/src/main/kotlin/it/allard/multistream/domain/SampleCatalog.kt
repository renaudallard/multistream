package it.allard.multistream.domain

import it.allard.multistream.core.model.Availability
import it.allard.multistream.core.model.AvailabilityType
import it.allard.multistream.core.model.Episode
import it.allard.multistream.core.model.ExternalIds
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderRef
import it.allard.multistream.core.model.Region
import it.allard.multistream.core.model.Season
import it.allard.multistream.core.model.Title
import it.allard.multistream.core.model.TitleKey
import it.allard.multistream.core.model.UnifiedSearchResult
import it.allard.multistream.core.model.normalizeTitle
import it.allard.multistream.core.model.titleKeyFor

/**
 * M0 scaffolding: a tiny built-in catalog with REAL provider deep links so the whole spine
 * (search -> detail -> track -> launch) is demonstrable before any provider implements real
 * catalog search. Replaced by live provider search in M1; remove once all providers search.
 */
object SampleCatalog {
    private val titles: List<Title> = listOf(
        series(
            imdb = "tt4574334",
            name = "Stranger Things",
            year = 2016,
            provider = ProviderId.NETFLIX,
            providerTitleId = "80057281",
            deepLinkHint = "https://www.netflix.com/title/80057281",
            episodesPerSeason = listOf(8, 9, 8, 9),
        ),
        series(
            imdb = "tt8111088",
            name = "The Mandalorian",
            year = 2019,
            provider = ProviderId.DISNEY,
            providerTitleId = "3jLIGMDYINqD",
            deepLinkHint = "https://www.disneyplus.com/series/the-mandalorian/3jLIGMDYINqD",
            episodesPerSeason = listOf(8, 8, 8),
        ),
        movie(
            imdb = "tt8097030",
            name = "Turning Red",
            year = 2022,
            provider = ProviderId.DISNEY,
            providerTitleId = "6Bcv9mGTPMYZ",
            deepLinkHint = "https://www.disneyplus.com/movies/turning-red/6Bcv9mGTPMYZ",
        ),
    )

    private val index: Map<String, Title> = titles.associateBy { it.key.serialize() }

    fun search(query: String): List<UnifiedSearchResult> {
        val normalized = normalizeTitle(query)
        if (normalized.isBlank()) return emptyList()
        return titles
            .filter { normalizeTitle(it.primaryTitle).contains(normalized) }
            .flatMap { title ->
                title.availabilities.map { availability ->
                    UnifiedSearchResult(
                        provider = availability.provider,
                        ref = availability.ref,
                        title = title.primaryTitle,
                        type = title.type,
                        year = title.year,
                        posterUrl = title.posterUrl,
                        availabilityType = availability.type,
                        externalIds = title.externalIds,
                    )
                }
            }
    }

    fun byKey(key: TitleKey): Title? = index[key.serialize()]

    private fun series(
        imdb: String,
        name: String,
        year: Int,
        provider: ProviderId,
        providerTitleId: String,
        deepLinkHint: String,
        episodesPerSeason: List<Int>,
    ): Title {
        val ids = ExternalIds(imdb = imdb)
        val ref = ProviderRef(provider, providerTitleId, deepLinkHint, Region.FR)
        val seasons = episodesPerSeason.mapIndexed { i, count ->
            val number = i + 1
            Season(number, "Season $number", (1..count).map { Episode(number, it, "Episode $it") })
        }
        return Title(
            key = titleKeyFor(name, year, ids, MediaType.SERIES),
            primaryTitle = name,
            type = MediaType.SERIES,
            year = year,
            synopsis = "Sample catalog entry (M0 scaffolding).",
            externalIds = ids,
            availabilities = listOf(Availability(provider, ref, AvailabilityType.SUBSCRIPTION, ref.region)),
            seasons = seasons,
        )
    }

    private fun movie(
        imdb: String,
        name: String,
        year: Int,
        provider: ProviderId,
        providerTitleId: String,
        deepLinkHint: String,
    ): Title {
        val ids = ExternalIds(imdb = imdb)
        val ref = ProviderRef(provider, providerTitleId, deepLinkHint, Region.FR)
        return Title(
            key = titleKeyFor(name, year, ids, MediaType.MOVIE),
            primaryTitle = name,
            type = MediaType.MOVIE,
            year = year,
            synopsis = "Sample catalog entry (M0 scaffolding).",
            externalIds = ids,
            availabilities = listOf(Availability(provider, ref, AvailabilityType.SUBSCRIPTION, ref.region)),
        )
    }
}
