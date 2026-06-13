package it.allard.multistream.core.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import it.allard.multistream.core.model.ProviderId
import it.allard.multistream.core.model.ProviderSecrets
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-provider session secrets in a Keystore-backed encrypted store. Resilient by design: if the
 * encrypted store can't be created (a corrupted keyset is the usual cause), it clears and retries
 * once, then falls back to an in-memory store so login degrades for the session instead of crashing
 * the app. Never throws. Never logs secret values.
 */
class SecretStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = ConcurrentHashMap<String, ProviderSecrets>()
    private val prefs: SharedPreferences? = createEncryptedPrefs(context.applicationContext)

    fun read(provider: ProviderId): ProviderSecrets {
        // memory holds writes that couldn't reach the encrypted store, so it takes precedence.
        memory[provider.name]?.let { return it }
        val store = prefs ?: return ProviderSecrets.EMPTY
        return store.getString(provider.name, null)
            ?.let { runCatching { json.decodeFromString<ProviderSecrets>(it) }.getOrNull() }
            ?: ProviderSecrets.EMPTY
    }

    fun write(provider: ProviderId, secrets: ProviderSecrets) {
        val store = prefs
        if (store == null) {
            memory[provider.name] = secrets
            return
        }
        // commit() (not apply()) so a failed disk write is actually observable here: apply() commits
        // in the background and never reports, which made the in-memory fallback unreachable and
        // dropped the safety copy before the write was durable. Callers run on Dispatchers.IO.
        val written = runCatching { store.edit().putString(provider.name, json.encodeToString(secrets)).commit() }
            .getOrDefault(false)
        if (written) {
            memory.remove(provider.name) // store is now authoritative; drop any stale fallback
        } else {
            memory[provider.name] = secrets
        }
    }

    fun clear(provider: ProviderId) {
        memory.remove(provider.name)
        // commit(), not apply(): a logout must be durable. apply() defers the disk write, so a crash
        // before it flushed would leave the secret on disk and silently log the user back in. Callers
        // run on Dispatchers.IO.
        runCatching { prefs?.edit()?.remove(provider.name)?.commit() }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences? = try {
        buildEncryptedPrefs(context)
    } catch (e: Exception) {
        // A corrupted keyset is the usual cause; clear the store and try once more.
        runCatching {
            context.deleteSharedPreferences(PREFS_NAME)
            buildEncryptedPrefs(context)
        }.getOrElse {
            Log.w(TAG, "EncryptedSharedPreferences unavailable; using in-memory secrets for this session", e)
            null
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val PREFS_NAME = "multistream_secrets"
        const val TAG = "SecretStore"
    }
}
