package it.allard.multistream.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.Region
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "multistream_settings")

/** Non-secret per-provider configuration: enabled flag and region. Reactive via DataStore. */
class SettingsRepository(private val context: Context) {

    // A corrupt or unreadable preferences file makes DataStore emit an IOException down the flow;
    // fall back to empty (defaults) rather than crashing search and settings, but rethrow anything else.
    private val preferences: Flow<Preferences>
        get() = context.settingsDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    fun enabledFlow(provider: ProviderId): Flow<Boolean> =
        preferences.map { it[enabledKey(provider)] ?: true }

    fun regionFlow(provider: ProviderId): Flow<Region?> =
        preferences.map { prefs -> prefs[regionKey(provider)]?.let { Region(it) } }

    suspend fun isEnabled(provider: ProviderId): Boolean = enabledFlow(provider).first()

    suspend fun region(provider: ProviderId): Region? = regionFlow(provider).first()

    // A failed settings write (corrupt or full storage raises IOException) must not crash the caller;
    // the preference simply isn't saved.
    suspend fun setEnabled(provider: ProviderId, enabled: Boolean) {
        try {
            context.settingsDataStore.edit { it[enabledKey(provider)] = enabled }
        } catch (e: IOException) {
        }
    }

    suspend fun setRegion(provider: ProviderId, region: Region) {
        try {
            context.settingsDataStore.edit { it[regionKey(provider)] = region.code }
        } catch (e: IOException) {
        }
    }

    private fun enabledKey(provider: ProviderId) = booleanPreferencesKey("enabled_${provider.name}")
    private fun regionKey(provider: ProviderId) = stringPreferencesKey("region_${provider.name}")
}
