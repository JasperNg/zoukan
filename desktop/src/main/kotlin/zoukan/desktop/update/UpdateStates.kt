package zoukan.desktop.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The update check's states and the only ways between them. Every transition is a compare-and-set,
 * so a double click, or a click during the daily check, cannot start a second check or download.
 *
 * - Idle, Result, Failed → Checking → Result or Failed. The automatic check never leaves
 *   Downloaded or Downloading, so a downloaded update keeps its Install button; a manual check
 *   from Downloaded finding the same version goes back to Downloaded.
 * - Result(Available) with an installer, or Failed after a download of it → Downloading →
 *   Downloaded or Failed (with the release, to try again).
 * - Downloaded → Failed (with the release) when the installer is rejected right before it runs,
 *   so the update is downloaded again instead of offering the same file forever.
 * - A cancelled check or download goes back to the state it started from.
 */
class UpdateStates(
    private val check: suspend () -> UpdateChecker.Result,
    private val download: suspend (UpdateChecker.Asset, (Long, Long) -> Unit) -> File,
) {
    sealed interface State {
        data object Idle : State

        data object Checking : State

        data class Result(val result: UpdateChecker.Result) : State

        data class Downloading(val release: UpdateChecker.Release, val read: Long, val total: Long) : State

        data class Downloaded(val release: UpdateChecker.Release, val file: File) : State

        /** [release] is set when a download failed, so it can be tried again. */
        data class Failed(val message: String, val release: UpdateChecker.Release? = null) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Moves from a state [allowed] accepts to [next], atomically; null when it is not allowed. */
    private fun enter(next: State, allowed: (State) -> Boolean): State? {
        while (true) {
            val current = mutableState.value
            if (!allowed(current)) return null
            if (mutableState.compareAndSet(current, next)) return current
        }
    }

    /**
     * Checks for an update. Returns the result, or null when the check did not run (another
     * check or a download is going on, or an automatic check while an update is downloaded).
     */
    suspend fun check(manual: Boolean): UpdateChecker.Result? {
        val previous = enter(State.Checking) {
            when (it) {
                State.Idle, is State.Result, is State.Failed -> true
                is State.Downloaded -> manual
                State.Checking, is State.Downloading -> false
            }
        } ?: return null
        val result = try {
            check()
        } catch (e: CancellationException) {
            mutableState.compareAndSet(State.Checking, previous)
            throw e
        } catch (e: Throwable) {
            // Also errors (a LinkageError, out of memory), so the state never stays at Checking
            UpdateChecker.Result.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
        val next = if (
            previous is State.Downloaded &&
            result is UpdateChecker.Result.Available &&
            result.release.version == previous.release.version &&
            previous.file.isFile
        ) {
            previous
        } else {
            State.Result(result)
        }
        mutableState.compareAndSet(State.Checking, next)
        return result
    }

    /** Downloads [release]'s installer. Returns the file, or null when it did not run or failed. */
    suspend fun download(release: UpdateChecker.Release): File? {
        val asset = release.asset ?: return null
        val previous = enter(State.Downloading(release, 0, asset.size)) {
            val available = ((it as? State.Result)?.result as? UpdateChecker.Result.Available)?.release
            available == release || (it is State.Failed && it.release == release)
        } ?: return null
        return try {
            val file = download(asset) { read, total ->
                // Only while this download is the state; never over a later one
                val current = mutableState.value
                if (current is State.Downloading && current.release == release) {
                    mutableState.compareAndSet(current, State.Downloading(release, read, total))
                }
            }
            mutableState.value = State.Downloaded(release, file)
            file
        } catch (e: CancellationException) {
            mutableState.value = previous
            throw e
        } catch (e: Throwable) {
            mutableState.value = State.Failed("Download failed: ${e.message ?: e::class.simpleName}", release)
            null
        }
    }

    /**
     * The downloaded [file] was rejected right before installing. Moves from the Downloaded state
     * holding that file to Failed with its release, to download it again. False when the state
     * is no longer that download.
     */
    fun installRejected(file: File, message: String): Boolean {
        val current = mutableState.value
        if (current !is State.Downloaded || current.file != file) return false
        return mutableState.compareAndSet(current, State.Failed(message, current.release))
    }
}
