package zoukan.desktop.app

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` that lets a coroutine's cancellation through. Use it around suspend calls: plain
 * `runCatching` turns the cancel into a failure result and the caller carries on.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
