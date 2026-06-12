package it.allard.multistream.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.allard.multistream.core.model.Genre
import it.allard.multistream.core.model.Title
import it.allard.multistream.di.ProviderRegistry
import it.allard.multistream.domain.SearchInteractor
import it.allard.multistream.launch.LaunchController
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.update.UpdateChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(
    private val interactor: SearchInteractor,
    private val registry: ProviderRegistry,
    private val launchController: LaunchController,
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val loading: Boolean = false,
        val searched: Boolean = false,
        val selectedGenre: Genre? = null,
        val genres: List<Genre> = emptyList(),
        val results: List<Title> = emptyList(),
        val degrade: List<StreamingProvider> = emptyList(),
        val failed: List<String> = emptyList(),
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        // The genre chips offer only genres some enabled provider can actually browse.
        viewModelScope.launch {
            val available = registry.enabled().flatMap { p ->
                if (p.capabilities.canBrowseByGenre) p.browsableGenres() else emptySet()
            }.toSet()
            _state.update { s -> s.copy(genres = Genre.entries.filter { it in available }) }
        }
    }

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }

    fun submit() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        // Each search is another chance to surface a pending app update if the launch check missed it.
        viewModelScope.launch { updateChecker.refresh() }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val degrade = registry.enabled().filter { !it.capabilities.canSearch }
            // Write the trimmed query back so the field shows what was actually searched, without trailing spaces.
            _state.update { it.copy(query = query, loading = true, searched = true, selectedGenre = null, results = emptyList(), degrade = degrade, failed = emptyList()) }
            interactor.search(query).collect { update ->
                _state.update { it.copy(loading = update.loading, results = update.results, failed = update.failed) }
            }
        }
    }

    /** Reset the search field and clear any results, returning the screen to its empty state. */
    fun clearQuery() {
        searchJob?.cancel()
        _state.update { it.copy(query = "", loading = false, searched = false, selectedGenre = null, results = emptyList(), degrade = emptyList(), failed = emptyList()) }
    }

    /** Browse a genre with no text query: stream merged results from every genre-capable provider. */
    fun browse(genre: Genre) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, searched = true, selectedGenre = genre, results = emptyList(), degrade = emptyList(), failed = emptyList()) }
            interactor.browseByGenre(genre).collect { update ->
                _state.update { it.copy(loading = update.loading, results = update.results, failed = update.failed) }
            }
        }
    }

    fun openInApp(provider: StreamingProvider) {
        viewModelScope.launch {
            val message = launchController.openApp(provider, _state.value.query.trim())
            _state.update { it.copy(message = message) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
