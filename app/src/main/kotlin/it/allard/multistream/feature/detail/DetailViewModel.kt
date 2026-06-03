package it.allard.multistream.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.allard.multistream.core.data.WatchRepository
import it.allard.multistream.core.data.db.WatchStatus
import it.allard.multistream.core.model.Availability
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.core.model.Title
import it.allard.multistream.core.model.TitleKey
import it.allard.multistream.core.model.computeNextEpisode
import it.allard.multistream.di.ProviderRegistry
import it.allard.multistream.domain.SearchInteractor
import it.allard.multistream.launch.LaunchController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DetailViewModel(
    private val key: TitleKey,
    private val interactor: SearchInteractor,
    private val watchRepository: WatchRepository,
    private val registry: ProviderRegistry,
    private val launchController: LaunchController,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val title: Title? = null,
        val watched: Set<EpisodeCoord> = emptySet(),
        val status: WatchStatus? = null,
        val inWatchlist: Boolean = false,
        val message: String? = null,
    ) {
        val nextEpisode: EpisodeCoord?
            get() = title?.let { computeNextEpisode(watched.maxOrNull(), it.seasons) }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val title = interactor.getTitle(key)
            _state.update { it.copy(loading = false, title = title) }
        }
        viewModelScope.launch {
            watchRepository.observeWatchedEpisodes(key).collect { episodes ->
                _state.update { it.copy(watched = episodes) }
            }
        }
        viewModelScope.launch {
            watchRepository.observeStatus(key).collect { status -> _state.update { it.copy(status = status) } }
        }
        viewModelScope.launch {
            watchRepository.observeInWatchlist(key).collect { inList -> _state.update { it.copy(inWatchlist = inList) } }
        }
    }

    fun toggleEpisode(coord: EpisodeCoord) {
        val title = _state.value.title ?: return
        val watched = coord !in _state.value.watched
        viewModelScope.launch { watchRepository.setEpisodeWatched(title, coord, watched) }
    }

    fun toggleWatchlist() {
        val title = _state.value.title ?: return
        viewModelScope.launch { watchRepository.setInWatchlist(title, !_state.value.inWatchlist) }
    }

    fun toggleMovieWatched() {
        val title = _state.value.title ?: return
        viewModelScope.launch { watchRepository.setMovieWatched(title, _state.value.status != WatchStatus.WATCHED) }
    }

    fun launch(availability: Availability, episode: EpisodeCoord? = null) {
        val provider = registry.get(availability.provider) ?: return
        val targetEpisode = episode?.takeIf { provider.capabilities.canDeepLinkToEpisode }
        _state.update { it.copy(message = launchController.launchTitle(provider, availability.ref, targetEpisode)) }
    }

    fun resume() {
        val title = _state.value.title ?: return
        val next = _state.value.nextEpisode ?: return
        val availability = title.availabilities.firstOrNull() ?: return
        launch(availability, next)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
