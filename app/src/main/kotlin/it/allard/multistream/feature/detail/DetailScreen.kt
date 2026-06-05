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
import androidx.compose.ui.unit.dp
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
    val viewModel = appViewModel {
        DetailViewModel(titleKey, graph.searchInteractor, graph.watchRepository, graph.registry, graph.launchController)
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
        TextButton(onClick = onBack) { Text("← Back") }

        val title = state.title
        when {
            state.loading -> CircularProgressIndicator()
            title == null -> Text("Title not found.")
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
                        "Cast: ${title.cast.take(8).joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                TextButton(onClick = viewModel::toggleWatchlist) {
                    Text(if (state.inWatchlist) "In Watchlist ✓" else "Add to Watchlist")
                }

                title.availabilities.forEach { availability ->
                    val provider = graph.registry.get(availability.provider)
                    Button(
                        onClick = { viewModel.launch(availability) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Play on ${provider?.displayName ?: availability.provider.name}")
                    }
                }

                if (title.type == MediaType.MOVIE) {
                    FilledTonalButton(onClick = viewModel::toggleMovieWatched, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.status == WatchStatus.WATCHED) "Watched ✓" else "Mark watched")
                    }
                } else {
                    state.nextEpisode?.let { next ->
                        FilledTonalButton(onClick = viewModel::resume, modifier = Modifier.fillMaxWidth()) {
                            Text("Resume S${next.season}E${next.episode}")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    title.seasons.forEach { season ->
                        Text(
                            season.title ?: "Season ${season.seasonNumber}",
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
