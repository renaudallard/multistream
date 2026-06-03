package it.allard.multistream.di

import it.allard.multistream.core.data.SettingsRepository
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.provider.api.StreamingProvider

/** The set of providers, with capability- and settings-based filtering. */
class ProviderRegistry(
    val providers: List<StreamingProvider>,
    private val settings: SettingsRepository,
) {
    fun get(id: ProviderId): StreamingProvider? = providers.firstOrNull { it.id == id }

    suspend fun enabled(): List<StreamingProvider> = providers.filter { settings.isEnabled(it.id) }

    suspend fun searchable(): List<StreamingProvider> =
        providers.filter { it.capabilities.canSearch && settings.isEnabled(it.id) }
}
