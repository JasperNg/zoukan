package zoukan.desktop.app

import kotlinx.coroutines.CancellationException
import xyz.nulldev.androidcompat.util.rethrowIfVmFailure

/**
 * `runCatching` that lets a coroutine's cancellation through. Use it around suspend calls: plain
 * `runCatching` turns the cancel into a failure result and the caller carries on.
 *
 * It also lets the VM's own failures through (running out of memory). An outdated extension's
 * `LinkageError` stays a failure: callers wrap calls into sources and extensions with it.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    e.rethrowIfVmFailure()
    Result.failure(e)
}
