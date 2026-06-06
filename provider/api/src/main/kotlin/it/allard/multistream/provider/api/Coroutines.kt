package it.allard.multistream.provider.api

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching] but never swallows coroutine cancellation: a [CancellationException] is rethrown
 * so a cancelled coroutine (for example a superseded search) actually stops, instead of being turned
 * into a failed [Result] that callers then treat as a normal error.
 */
inline fun <T> runCatchingExceptCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
