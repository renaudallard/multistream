package it.allard.multistream.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.allard.multistream.R
import it.allard.multistream.core.data.SecretStore
import it.allard.multistream.core.data.SettingsRepository
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.Region
import it.allard.multistream.di.ProviderRegistry
import it.allard.multistream.provider.api.StreamingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class SettingsViewModel(
    private val registry: ProviderRegistry,
    private val settings: SettingsRepository,
    private val appContext: Context,
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
            val initial = registry.providers.associate { it.id to !secrets().read(it.id).isEmpty }
            // Merge under any login/logout that landed while we were reading, so we don't revert it.
            _loggedIn.update { current -> initial + current }
        }
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** A device-link login in progress: the code to show and the page to enter it on. */
    data class LinkPrompt(val providerId: ProviderId, val code: String, val url: String)

    private val _linkPrompt = MutableStateFlow<LinkPrompt?>(null)
    val linkPrompt: StateFlow<LinkPrompt?> = _linkPrompt.asStateFlow()
    private var linkJob: Job? = null

    fun setEnabled(provider: StreamingProvider, enabled: Boolean) {
        viewModelScope.launch { settings.setEnabled(provider.id, enabled) }
    }

    fun setRegion(provider: StreamingProvider, region: Region) {
        viewModelScope.launch { settings.setRegion(provider.id, region) }
    }

    fun login(provider: StreamingProvider, email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _message.value = appContext.getString(
                R.string.msg_enter_credentials,
                provider.capabilities.loginUserLabel,
                provider.capabilities.loginPassLabel,
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val secret = provider.login(email, password) ?: error("Login not supported")
                secrets().write(provider.id, secret)
            }
            if (result.isSuccess) {
                _loggedIn.update { it + (provider.id to true) }
                _message.value = appContext.getString(R.string.msg_logged_in, provider.displayName)
            } else {
                _message.value = appContext.getString(
                    R.string.msg_status,
                    provider.displayName,
                    result.exceptionOrNull()?.message ?: appContext.getString(R.string.msg_login_failed),
                )
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
                _message.value = appContext.getString(R.string.msg_logged_in, provider.displayName)
            } else {
                _message.value = appContext.getString(
                    R.string.msg_status,
                    provider.displayName,
                    result.exceptionOrNull()?.message ?: appContext.getString(R.string.msg_login_failed),
                )
            }
        }
    }

    fun startLink(provider: StreamingProvider) {
        if (linkJob?.isActive == true || _linkPrompt.value != null) {
            _message.value = appContext.getString(R.string.msg_linking_in_progress)
            return
        }
        linkJob = viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { provider.beginLink() }.getOrNull()
            if (session == null) {
                _message.value = appContext.getString(R.string.msg_linking_unavailable, provider.displayName)
                return@launch
            }
            _linkPrompt.value = LinkPrompt(provider.id, session.code, session.verificationUrl)
            val secret = try {
                session.awaitToken()
            } catch (e: CancellationException) {
                throw e // the user cancelled: stop the poll instead of reporting a timeout
            } catch (e: Exception) {
                null
            }
            _linkPrompt.value = null
            if (secret != null) {
                secrets().write(provider.id, secret)
                _loggedIn.update { it + (provider.id to true) }
                _message.value = appContext.getString(R.string.msg_linked, provider.displayName)
            } else {
                _message.value = appContext.getString(R.string.msg_linking_timed_out, provider.displayName)
            }
        }
    }

    /** Abandon a device link in progress so its poll stops (the user dismissed the code prompt). */
    fun cancelLink() {
        linkJob?.cancel()
        linkJob = null
        _linkPrompt.value = null
    }

    fun logout(provider: StreamingProvider) {
        viewModelScope.launch(Dispatchers.IO) {
            secrets().clear(provider.id)
            _loggedIn.update { it + (provider.id to false) }
            _message.value = appContext.getString(R.string.msg_logged_out, provider.displayName)
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
