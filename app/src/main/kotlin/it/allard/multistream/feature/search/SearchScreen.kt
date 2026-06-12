package it.allard.multistream.feature.search

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import it.allard.multistream.R
import it.allard.multistream.core.model.Genre
import it.allard.multistream.core.model.Title
import it.allard.multistream.core.model.TitleKey
import it.allard.multistream.di.LocalAppGraph
import it.allard.multistream.ui.appViewModel
import it.allard.multistream.ui.components.TitleCard
import kotlinx.coroutines.launch
import java.text.Normalizer

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
    val scope = rememberCoroutineScope()
    var wasLoading by remember { mutableStateOf(false) }
    // When a search finishes ranking, jump back to the top so the most relevant results are in view.
    // Tracking the loading edge keeps a plain return to this screen from resetting the scroll position.
    LaunchedEffect(state.loading) {
        if (wasLoading && !state.loading) listState.scrollToItem(0)
        wasLoading = state.loading
    }

    // Genre browse returns an alphabetically sorted list, so build a fast-scroll index over it: each
    // distinct initial that is present, mapped to the first result starting with it. Empty for text search.
    val letterIndex = remember(state.results, state.selectedGenre) {
        if (state.selectedGenre == null) {
            emptyList()
        } else {
            val firstByLetter = LinkedHashMap<String, Int>()
            state.results.forEachIndexed { i, title -> firstByLetter.getOrPut(title.initialLetter()) { i } }
            firstByLetter.entries.map { it.key to it.value }
        }
    }
    var activeLetter by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            label = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
            trailingIcon = {
                Row {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearQuery) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                    IconButton(onClick = viewModel::submit) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.tab_search))
                    }
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
                        label = { Text(stringResource(genre.labelRes())) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        // The A-Z strip only earns its space on the sorted genre list with at least a couple of initials.
        val showLetterIndex = letterIndex.size >= 2
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(end = if (showLetterIndex) 24.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
            if (showLetterIndex) {
                LetterIndexBar(
                    letters = letterIndex.map { it.first },
                    onPick = { i ->
                        val letter = letterIndex[i].first
                        if (letter != activeLetter) {
                            activeLetter = letter
                            scope.launch { listState.scrollToItem(letterIndex[i].second) }
                        }
                    },
                    onRelease = { activeLetter = null },
                )
                activeLetter?.let { LetterBubble(it) }
            }
        }
    }
}

/** Vertical A-Z strip pinned to the right edge: tap a letter to jump, or drag to scrub through them. */
@Composable
private fun BoxScope.LetterIndexBar(letters: List<String>, onPick: (Int) -> Unit, onRelease: () -> Unit) {
    Column(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(24.dp)
            .pointerInput(letters) {
                awaitEachGesture {
                    fun pick(y: Float) {
                        if (letters.isEmpty()) return
                        val i = (y / this@pointerInput.size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)
                        onPick(i)
                    }
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pick(down.position.y)
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change != null && change.pressed) {
                            pick(change.position.y)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    onRelease()
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(letter, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The large letter preview shown in the middle of the list while a letter is tapped or dragged. */
@Composable
private fun BoxScope.LetterBubble(letter: String) {
    Surface(
        modifier = Modifier.align(Alignment.Center).size(72.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(letter, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** Index initial for a title: accents fold to their base letter (E for Étoile), digits/symbols group under "#". */
private fun Title.initialLetter(): String {
    val first = primaryTitle.trimStart().firstOrNull() ?: return "#"
    val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first()
    return if (base.isLetter()) base.uppercaseChar().toString() else "#"
}

/** Localized display name for a browse genre; the model keeps only the constant. */
@StringRes
private fun Genre.labelRes(): Int = when (this) {
    Genre.COMEDY -> R.string.genre_comedy
    Genre.DRAMA -> R.string.genre_drama
    Genre.HORROR -> R.string.genre_horror
    Genre.ACTION -> R.string.genre_action
    Genre.DOCUMENTARY -> R.string.genre_documentary
    Genre.SCIFI -> R.string.genre_scifi
    Genre.CRIME -> R.string.genre_crime
    Genre.ROMANCE -> R.string.genre_romance
    Genre.ANIMATION -> R.string.genre_animation
    Genre.KIDS -> R.string.genre_kids
}
