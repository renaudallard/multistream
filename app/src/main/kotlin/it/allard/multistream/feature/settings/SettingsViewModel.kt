package it.allard.multistream.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.allard.multistream.core.data.SettingsRepository
import it.allard.multistream.core.model.Region
import it.allard.multistream.di.ProviderRegistry
import it.allard.multistream.provider.api.StreamingProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val registry: ProviderRegistry,
    private val settings: SettingsRepository,
) : ViewModel() {

    data class Row(val provider: StreamingProvider, val enabled: Boolean, val region: Region?)

    val rows: StateFlow<List<Row>> =
        combine(
            registry.providers.map { provider ->
                combine(settings.enabledFlow(provider.id), settings.regionFlow(provider.id)) { enabled, region ->
                    Row(provider, enabled, region)
                }
            },
        ) { it.toList() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(provider: StreamingProvider, enabled: Boolean) {
        viewModelScope.launch { settings.setEnabled(provider.id, enabled) }
    }

    fun setRegion(provider: StreamingProvider, region: Region) {
        viewModelScope.launch { settings.setRegion(provider.id, region) }
    }
}
