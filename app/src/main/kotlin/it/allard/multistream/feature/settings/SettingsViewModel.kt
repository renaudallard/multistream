package it.allard.multistream.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.allard.multistream.core.data.SecretStore
import it.allard.multistream.core.data.SettingsRepository
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.Region
import it.allard.multistream.di.ProviderRegistry
import it.allard.multistream.provider.api.StreamingProvider
import kotlinx.coroutines.Dispatchers
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
    // A lazy accessor: resolving it builds the Keystore-backed store, which must not run on the main
    // thread, so the first touch happens inside the IO coroutine below.
    private val secrets: () -> SecretStore,
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

    private val _loggedIn = MutableStateFlow<Map<ProviderId, Boolean>>(emptyMap())
    val loggedIn: StateFlow<Map<ProviderId, Boolean>> = _loggedIn.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _loggedIn.value = registry.providers.associate { it.id to !secrets().read(it.id).isEmpty }
        }
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** A device-link login in progress: the code to show and the page to enter it on. */
    data class LinkPrompt(val providerId: ProviderId, val code: String, val url: String)

    private val _linkPrompt = MutableStateFlow<LinkPrompt?>(null)
    val linkPrompt: StateFlow<LinkPrompt?> = _linkPrompt.asStateFlow()

    fun setEnabled(provider: StreamingProvider, enabled: Boolean) {
        viewModelScope.launch { settings.setEnabled(provider.id, enabled) }
    }

    fun setRegion(provider: StreamingProvider, region: Region) {
        viewModelScope.launch { settings.setRegion(provider.id, region) }
    }

    fun login(provider: StreamingProvider, email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _message.value = "Enter ${provider.capabilities.loginUserLabel} and ${provider.capabilities.loginPassLabel}"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val secret = provider.login(email, password) ?: error("Login not supported")
                secrets().write(provider.id, secret)
            }
            if (result.isSuccess) {
                _loggedIn.update { it + (provider.id to true) }
                _message.value = "${provider.displayName}: logged in"
            } else {
                _message.value = "${provider.displayName}: ${result.exceptionOrNull()?.message ?: "login failed"}"
            }
        }
    }

    fun loginWithCookies(provider: StreamingProvider, cookies: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val secret = provider.loginWithCookies(cookies) ?: error("WebView login not supported")
                secrets().write(provider.id, secret)
            }
            if (result.isSuccess) {
                _loggedIn.update { it + (provider.id to true) }
                _message.value = "${provider.displayName}: logged in"
            } else {
                _message.value = "${provider.displayName}: ${result.exceptionOrNull()?.message ?: "login failed"}"
            }
        }
    }

    fun startLink(provider: StreamingProvider) {
        if (_linkPrompt.value != null) return // a link is already in progress
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { provider.beginLink() }.getOrNull()
            if (session == null) {
                _message.value = "${provider.displayName}: linking unavailable"
                return@launch
            }
            _linkPrompt.value = LinkPrompt(provider.id, session.code, session.verificationUrl)
            val secret = runCatching { session.awaitToken() }.getOrNull()
            _linkPrompt.value = null
            if (secret != null) {
                secrets().write(provider.id, secret)
                _loggedIn.update { it + (provider.id to true) }
                _message.value = "${provider.displayName}: linked"
            } else {
                _message.value = "${provider.displayName}: linking timed out — try again"
            }
        }
    }

    fun logout(provider: StreamingProvider) {
        viewModelScope.launch(Dispatchers.IO) {
            secrets().clear(provider.id)
            _loggedIn.update { it + (provider.id to false) }
            _message.value = "${provider.displayName}: logged out"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
