package it.allard.multistream.feature.detail

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.allard.multistream.R
import it.allard.multistream.core.data.db.WatchStatus
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.MediaType
import it.allard.multistream.core.model.TitleKey
import it.allard.multistream.di.LocalAppGraph
import it.allard.multistream.ui.appViewModel
import it.allard.multistream.ui.components.PosterImage
import it.allard.multistream.ui.components.ProviderBadge

@Composable
fun DetailScreen(titleKey: TitleKey, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val appContext = LocalContext.current.applicationContext
    val viewModel = appViewModel {
        DetailViewModel(titleKey, graph.searchInteractor, graph.watchRepository, graph.registry, graph.launchController, appContext)
    }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }

        val title = state.title
        when {
            state.loading -> CircularProgressIndicator()
            title == null -> Text(stringResource(R.string.detail_title_not_found))
            else -> {
                Row {
                    PosterImage(title.posterUrl, Modifier.size(width = 100.dp, height = 150.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title.primaryTitle, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                title.year?.let { append(it).append(" · ") }
                                append(title.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
                                state.status?.let { append(" · ").append(it.name.lowercase()) }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            title.availabilities.forEach { ProviderBadge(it.provider.name) }
                        }
                    }
                }
                title.synopsis?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (title.cast.isNotEmpty()) {
                    Text(
                        stringResource(R.string.detail_cast, title.cast.take(8).joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TextButton(onClick = viewModel::toggleWatchlist) {
                    Text(stringResource(if (state.inWatchlist) R.string.detail_in_watchlist else R.string.detail_add_to_watchlist))
                }

                title.availabilities.forEach { availability ->
                    val provider = graph.registry.get(availability.provider)
                    Button(
                        onClick = { viewModel.launch(availability) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.detail_play_on, provider?.displayName ?: availability.provider.name))
                    }
                }

                if (title.type == MediaType.MOVIE) {
                    FilledTonalButton(onClick = viewModel::toggleMovieWatched, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (state.status == WatchStatus.WATCHED) R.string.detail_watched else R.string.detail_mark_watched))
                    }
                } else {
                    state.nextEpisode?.let { next ->
                        FilledTonalButton(onClick = viewModel::resume, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.detail_resume_episode, next.season, next.episode))
                        }
                    }
                    val watchStateProvider = title.availabilities.firstNotNullOfOrNull {
                        graph.registry.get(it.provider)?.takeIf { p -> p.capabilities.canFetchWatchState }
                    }
                    if (watchStateProvider != null) {
                        FilledTonalButton(onClick = viewModel::importWatched, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.detail_sync_watched, watchStateProvider.displayName))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    title.seasons.forEach { season ->
                        Text(
                            season.title ?: stringResource(R.string.detail_season_number, season.seasonNumber),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        season.episodes.forEach { episode ->
                            val coord = EpisodeCoord(episode.seasonNumber, episode.episodeNumber)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = coord in state.watched,
                                    onCheckedChange = { viewModel.toggleEpisode(coord) },
                                )
                                Text(
                                    "S${episode.seasonNumber}E${episode.episodeNumber}" +
                                        (episode.title?.let { " · $it" } ?: ""),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
