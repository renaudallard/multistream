package it.allard.multistream.feature.search

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import it.allard.multistream.R
import it.allard.multistream.core.model.TitleKey
import it.allard.multistream.di.LocalAppGraph
import it.allard.multistream.ui.appViewModel
import it.allard.multistream.ui.components.TitleCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onOpenTitle: (TitleKey) -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel = appViewModel { SearchViewModel(graph.searchInteractor, graph.registry, graph.launchController) }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    val listState = rememberLazyListState()
    var wasLoading by remember { mutableStateOf(false) }
    // When a search finishes ranking, jump back to the top so the most relevant results are in view.
    // Tracking the loading edge keeps a plain return to this screen from resetting the scroll position.
    LaunchedEffect(state.loading) {
        if (wasLoading && !state.loading) listState.scrollToItem(0)
        wasLoading = state.loading
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            label = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
            trailingIcon = {
                IconButton(onClick = viewModel::submit) {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.tab_search))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        // With an empty query, offer a row of genre chips to browse by genre instead of typing.
        if (state.query.isBlank() && state.genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.genres.forEach { genre ->
                    FilterChip(
                        selected = state.selectedGenre == genre,
                        onClick = { viewModel.browse(genre) },
                        label = { Text(genre.label) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.results, key = { it.key.serialize() }) { title ->
                TitleCard(title) { onOpenTitle(title.key) }
            }
            if (state.searched && state.results.isEmpty() && !state.loading) {
                item { Text(stringResource(R.string.search_no_matches), style = MaterialTheme.typography.bodyMedium) }
            }
            if (state.searched && state.degrade.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.search_direct_in),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(state.degrade, key = { it.id.name }) { provider ->
                    ListItem(
                        headlineContent = { Text(provider.displayName) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (provider.capabilities.canInAppSearchDeepLink) R.string.search_opens_in_app else R.string.search_opens_app,
                                ),
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { viewModel.openInApp(provider) }) { Text(stringResource(R.string.action_open)) }
                        },
                    )
                }
            }
        }
    }
}
