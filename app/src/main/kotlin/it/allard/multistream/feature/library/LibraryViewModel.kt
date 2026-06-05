package it.allard.multistream.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.allard.multistream.core.data.LibraryEntry
import it.allard.multistream.core.data.WatchRepository
import it.allard.multistream.core.model.EpisodeCoord
import it.allard.multistream.di.ProviderRegistry
import it.allard.multistream.domain.SearchInteractor
import it.allard.multistream.launch.LaunchController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val watchRepository: WatchRepository,
    private val interactor: SearchInteractor,
    private val registry: ProviderRegistry,
    private val launchController: LaunchController,
) : ViewModel() {

    private val started = SharingStarted.WhileSubscribed(5_000)

    val continueWatching: StateFlow<List<LibraryEntry>> =
        watchRepository.continueWatching().stateIn(viewModelScope, started, emptyList())
    val watchlist: StateFlow<List<LibraryEntry>> =
        watchRepository.watchlist().stateIn(viewModelScope, started, emptyList())
    val history: StateFlow<List<LibraryEntry>> =
        watchRepository.history().stateIn(viewModelScope, started, emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun open(entry: LibraryEntry) {
        viewModelScope.launch {
            // After a restart the in-memory search index is empty, so fall back to the launch target
            // persisted when the title was tracked.
            val ref = interactor.getTitle(entry.key)?.availabilities?.firstOrNull()?.ref
                ?: watchRepository.launchRef(entry.key)
                ?: return@launch
            val provider = registry.get(ref.provider) ?: return@launch
            val season = entry.nextSeason
            val episodeNumber = entry.nextEpisode
            val episode = if (season != null && episodeNumber != null && provider.capabilities.canDeepLinkToEpisode) {
                EpisodeCoord(season, episodeNumber)
            } else {
                null
            }
            _message.value = launchController.launchTitle(provider, ref, episode)
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
