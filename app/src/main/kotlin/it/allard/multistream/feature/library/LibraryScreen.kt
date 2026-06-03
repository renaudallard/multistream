package it.allard.multistream.feature.library

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
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
import it.allard.multistream.core.data.LibraryEntry
import it.allard.multistream.core.model.TitleKey
import it.allard.multistream.di.LocalAppGraph
import it.allard.multistream.ui.appViewModel

@Composable
fun LibraryScreen(onOpenTitle: (TitleKey) -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel = appViewModel {
        LibraryViewModel(graph.watchRepository, graph.searchInteractor, graph.registry, graph.launchController)
    }
    val continueWatching by viewModel.continueWatching.collectAsState()
    val watchlist by viewModel.watchlist.collectAsState()
    val history by viewModel.history.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        section("Continue Watching", continueWatching, viewModel::open, onOpenTitle)
        section("Watchlist", watchlist, viewModel::open, onOpenTitle)
        section("History", history, viewModel::open, onOpenTitle)
        if (continueWatching.isEmpty() && watchlist.isEmpty() && history.isEmpty()) {
            item {
                Text(
                    "Nothing tracked yet. Search for a show, open it, and mark episodes watched.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun LazyListScope.section(
    title: String,
    entries: List<LibraryEntry>,
    onOpen: (LibraryEntry) -> Unit,
    onDetail: (TitleKey) -> Unit,
) {
    if (entries.isEmpty()) return
    item(key = "header-$title") {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    }
    items(entries, key = { "$title-${it.key.serialize()}" }) { entry ->
        LibraryRow(entry, onOpen = { onOpen(entry) }, onDetail = { onDetail(entry.key) })
    }
}

@Composable
private fun LibraryRow(entry: LibraryEntry, onOpen: () -> Unit, onDetail: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                val sub = buildString {
                    append(entry.status.name.lowercase().replaceFirstChar { it.uppercase() })
                    if (entry.nextSeason != null && entry.nextEpisode != null) {
                        append(" · Next S${entry.nextSeason}E${entry.nextEpisode}")
                    }
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onDetail) { Text("Details") }
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}
