package it.allard.multistream.core.model

import kotlinx.serialization.Serializable

/** Per-provider session secrets, stored encrypted on-device (never logged, never in Room/DataStore). */
@Serializable
data class ProviderSecrets(
    val token: String? = null,
    val refreshToken: String? = null,
    val cookie: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = token == null && refreshToken == null && cookie == null && extra.isEmpty()

    companion object {
        val EMPTY = ProviderSecrets()
    }
}
