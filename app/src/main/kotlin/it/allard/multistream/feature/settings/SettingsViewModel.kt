package it.allard.multistream.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.allard.multistream.core.data.SecretStore
import it.allard.multistream.core.data.SettingsRepository
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.Region
import it.allard.multistream.di.ProviderRegistry
import it.allard.multistream.provider.api.StreamingProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val registry: ProviderRegistry,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
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

    private val _loggedIn = MutableStateFlow(
        registry.providers.associate { it.id to !secrets.read(it.id).isEmpty },
    )
    val loggedIn: StateFlow<Map<ProviderId, Boolean>> = _loggedIn.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setEnabled(provider: StreamingProvider, enabled: Boolean) {
        viewModelScope.launch { settings.setEnabled(provider.id, enabled) }
    }

    fun setRegion(provider: StreamingProvider, region: Region) {
        viewModelScope.launch { settings.setRegion(provider.id, region) }
    }

    fun login(provider: StreamingProvider, email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _message.value = "Enter an email and password"
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                val secret = provider.login(email, password) ?: error("Login not supported")
                secrets.write(provider.id, secret)
            }
            if (result.isSuccess) {
                _loggedIn.update { it + (provider.id to true) }
                _message.value = "${provider.displayName}: logged in"
            } else {
                _message.value = "${provider.displayName}: ${result.exceptionOrNull()?.message ?: "login failed"}"
            }
        }
    }

    fun logout(provider: StreamingProvider) {
        secrets.clear(provider.id)
        _loggedIn.update { it + (provider.id to false) }
        _message.value = "${provider.displayName}: logged out"
    }

    fun consumeMessage() {
        _message.value = null
    }
}
