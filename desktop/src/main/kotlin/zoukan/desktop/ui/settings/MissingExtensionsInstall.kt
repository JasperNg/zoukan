package zoukan.desktop.ui.settings

import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.backup.RestoreGate
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.AppScope
import zoukan.desktop.ui.common.Toasts
import zoukan.extension.DesktopExtensionManager
import zoukan.extension.LoadResult
import zoukan.i18n.desktop.DMR

/**
 * "Install missing extensions" after a restore (desktop only). Runs in [AppScope], so the
 * installs go on when the user leaves the restore screen; [state] is what that screen shows.
 */
object MissingExtensionsInstall {

    sealed interface State {
        data object Idle : State
        data object Finding : State

        /** Finding, once the restore that is running ends: its library is only partly restored. */
        data object WaitingForRestore : State
        data class Ready(val plan: MissingExtensionsPlan) : State
        data class Installing(val done: Int, val total: Int, val current: String?) : State
        data class Done(val summary: MissingExtensionsSummary) : State
    }

    /**
     * [state] belongs to the restore [owner] names. [queued] is a restore whose [find] waits for the
     * running find or install to end.
     */
    data class Snapshot(val owner: Any?, val state: State, val queued: Any?) {
        /** What the restore [token] shows: its own state, Finding while its find waits, else Idle. */
        fun forRestore(token: Any): State = when {
            owner == token -> state
            queued == token -> State.Finding
            else -> State.Idle
        }

        /**
         * What Settings > Data and storage's screen [token] shows: as [forRestore], except that while
         * its find waits for an install, it shows that install's progress instead of a find that
         * has not started. That screen is where the user comes back to see a running install.
         */
        fun forSettings(token: Any): State =
            if (queued == token && state is State.Installing) state else forRestore(token)
    }

    private val mutableState = MutableStateFlow(Snapshot(owner = null, state = State.Idle, queued = null))
    val state: StateFlow<Snapshot> = mutableState.asStateFlow()

    /** Works out the plan; replaced in tests. */
    internal var planner: suspend () -> MissingExtensionsPlan = { findPlan() }

    /** Installs one extension; replaced in tests. */
    internal var installer: (suspend (Extension.Available) -> LoadResult)? = null

    /**
     * Refreshes the store lists, as the Browse screen does, and works out which library sources
     * are still missing and which extensions offer them, for the restore [token]. While another
     * find or an install runs, it runs when that ends, so a newer restore's sources are always found.
     */
    fun find(token: Any) {
        synchronized(this) {
            val current = mutableState.value
            if (current.state.isRunning) {
                if (current.owner != token) mutableState.value = current.copy(queued = token)
                return
            }
            mutableState.value = Snapshot(owner = token, state = State.Finding, queued = null)
        }
        AppScope.launch {
            try {
                if (RestoreGate.isRestoring) {
                    setState(token, State.WaitingForRestore)
                    RestoreGate.awaitNoRestore()
                    setState(token, State.Finding)
                }
                val plan = planner()
                setState(token, State.Ready(plan))
            } catch (e: CancellationException) {
                setState(token, State.Idle)
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Finding the missing extensions failed" }
                Toasts.error(localized(DMR.strings.could_not_load_the_extension_lists), e)
                setState(token, State.Idle)
            } finally {
                runQueued()
            }
        }
    }

    /** Installs the extensions of the plan [find] made for [token]. Never trusts a signer by itself. */
    fun install(token: Any) {
        val plan = synchronized(this) {
            val current = mutableState.value
            if (current.owner != token) return
            val ready = current.state as? State.Ready ?: return
            mutableState.value = current.copy(state = State.Installing(0, ready.plan.toInstall.size, null))
            ready.plan
        }
        val install: suspend (Extension.Available) -> LoadResult =
            installer ?: Injekt.get<DesktopExtensionManager>()::installExtension
        AppScope.launch {
            try {
                val summary = installMissingExtensions(plan, install) { done, total, current ->
                    setState(token, State.Installing(done, total, current))
                }
                setState(token, State.Done(summary))
                Toasts.show(localized(DMR.strings.missing_extensions_installed, summary.installed.size, plan.toInstall.size))
            } catch (e: CancellationException) {
                setState(token, State.Idle)
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Installing the missing extensions failed" }
                Toasts.error(localized(MR.strings.unknown_error), e)
                setState(token, State.Idle)
            } finally {
                runQueued()
            }
        }
    }

    /** Back to nothing shown, unless work is running. */
    fun dismiss() {
        synchronized(this) {
            val current = mutableState.value
            if (current.state is State.Ready || current.state is State.Done) {
                mutableState.value = current.copy(owner = null, state = State.Idle)
            }
        }
    }

    /** Sets [token]'s state, unless a newer restore owns [state] by now. */
    private fun setState(token: Any, state: State) {
        synchronized(this) {
            val current = mutableState.value
            if (current.owner == token) mutableState.value = current.copy(state = state)
        }
    }

    private fun runQueued() {
        val next = synchronized(this) {
            val current = mutableState.value
            val queued = current.queued ?: return
            if (current.state.isRunning) return
            mutableState.value = current.copy(queued = null)
            queued
        }
        find(next)
    }

    private val State.isRunning: Boolean
        get() = this is State.Finding || this is State.WaitingForRestore || this is State.Installing

    private suspend fun findPlan(): MissingExtensionsPlan {
        val manager = Injekt.get<DesktopExtensionManager>()
        val sourceManager = Injekt.get<SourceManager>()
        manager.isInitialized.first { it }
        sourceManager.isInitialized.first { it }
        manager.findAvailableExtensions().forEach { (store, result) ->
            result.onFailure { Toasts.error(localized(DMR.strings.could_not_load, store.name), it) }
        }
        val missing = Injekt.get<GetFavorites>().await()
            .map { it.source }
            .distinct()
            .filter { sourceManager.get(it) == null }
            .map { id -> sourceManager.getOrStub(id).let { MissingSource(id, it.toString(), it.name) } }
        return MissingExtensionsPlan.of(
            missing = missing,
            offers = manager.availableExtensionsFlow.value.values,
            installed = manager.installedExtensionsFlow.value,
            untrusted = manager.untrustedExtensionsFlow.value,
        )
    }
}
