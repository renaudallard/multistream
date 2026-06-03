package it.allard.multistream.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderSecrets
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Per-provider session secrets in a Keystore-backed encrypted store. Never logged. */
class SecretStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "multistream_secrets",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun read(provider: ProviderId): ProviderSecrets =
        prefs.getString(provider.name, null)
            ?.let { runCatching { json.decodeFromString<ProviderSecrets>(it) }.getOrNull() }
            ?: ProviderSecrets.EMPTY

    fun write(provider: ProviderId, secrets: ProviderSecrets) {
        prefs.edit().putString(provider.name, json.encodeToString(secrets)).apply()
    }

    fun clear(provider: ProviderId) {
        prefs.edit().remove(provider.name).apply()
    }
}
