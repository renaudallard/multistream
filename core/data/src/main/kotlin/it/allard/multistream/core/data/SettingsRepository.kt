package it.allard.multistream.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.Region
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "multistream_settings")

/** Non-secret per-provider configuration: enabled flag and region. Reactive via DataStore. */
class SettingsRepository(private val context: Context) {

    fun enabledFlow(provider: ProviderId): Flow<Boolean> =
        context.settingsDataStore.data.map { it[enabledKey(provider)] ?: true }

    fun regionFlow(provider: ProviderId): Flow<Region?> =
        context.settingsDataStore.data.map { prefs -> prefs[regionKey(provider)]?.let { Region(it) } }

    suspend fun isEnabled(provider: ProviderId): Boolean = enabledFlow(provider).first()

    suspend fun region(provider: ProviderId): Region? = regionFlow(provider).first()

    suspend fun setEnabled(provider: ProviderId, enabled: Boolean) {
        context.settingsDataStore.edit { it[enabledKey(provider)] = enabled }
    }

    suspend fun setRegion(provider: ProviderId, region: Region) {
        context.settingsDataStore.edit { it[regionKey(provider)] = region.code }
    }

    private fun enabledKey(provider: ProviderId) = booleanPreferencesKey("enabled_${provider.name}")
    private fun regionKey(provider: ProviderId) = stringPreferencesKey("region_${provider.name}")
}
