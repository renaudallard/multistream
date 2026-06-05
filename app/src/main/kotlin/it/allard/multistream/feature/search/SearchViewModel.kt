package it.allard.multistream.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.allard.multistream.core.model.Title
import it.allard.multistream.di.ProviderRegistry
import it.allard.multistream.domain.SearchInteractor
import it.allard.multistream.launch.LaunchController
import it.allard.multistream.provider.api.StreamingProvider
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
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val loading: Boolean = false,
        val searched: Boolean = false,
        val results: List<Title> = emptyList(),
        val degrade: List<StreamingProvider> = emptyList(),
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }

    fun submit() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val degrade = registry.enabled().filter { !it.capabilities.canSearch }
            _state.update { it.copy(loading = true, searched = true, results = emptyList(), degrade = degrade) }
            interactor.search(query).collect { update ->
                _state.update { it.copy(loading = update.loading, results = update.results) }
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
