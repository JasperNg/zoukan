package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.Toasts
import zoukan.i18n.desktop.DMR
import java.io.File

/** How a restore that [BackupJobs.restore] started ended. */
enum class RestoreOutcome { OK, FAILED, CANCELLED }

/** Asks whether a restore may add the extension stores a backup lists: true adds, false restores without, null cancels. */
fun interface StorePrompt {
    fun confirm(stores: List<String>): Boolean?
}

/** Runs the backups and restores started from Settings, one at a time, and reports what is running. */
class BackupJobs(
    private val scope: CoroutineScope,
    private val backups: () -> DesktopBackups,
    private val storePrompt: StorePrompt,
) {
    private val _status = MutableStateFlow<String?>(null)

    /** What a backup or restore is doing, shown in place of its buttons; null when none runs. */
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Writes a backup of what [options] picks (Create backup's choices) to [file]. */
    fun create(file: File, options: BackupOptions) {
        if (_status.value != null) return
        _status.value = localized(DMR.strings.backing_up)
        scope.launch {
            try {
                val backup = backups().create(file, options)
                Toasts.show(localized(DMR.strings.backed_up_entries_to, backup.backupManga.size, file.name))
            } catch (e: Exception) {
                Toasts.error(localized(MR.strings.creating_backup_error), e)
            } finally {
                _status.value = null
            }
        }
    }

    /**
     * Restores what [options] picks (Restore backup's choices) from [file]. Returns null, and does
     * nothing, while another backup or restore runs; otherwise how the restore ends.
     */
    fun restore(file: File, options: RestoreOptions): Deferred<RestoreOutcome>? {
        if (!_status.compareAndSet(null, localized(DMR.strings.restoring, file.name))) return null
        val outcome = CompletableDeferred<RestoreOutcome>()
        scope.launch {
            var result = RestoreOutcome.FAILED
            try {
                // The gate holds the background jobs off while the library is half-restored
                RestoreGate.whileRestoring {
                    try {
                        val desktopBackups = backups()
                        val backup = desktopBackups.read(file)
                        // Extension stores decide whose code runs, so a backup adds them only when the user
                        // has seen which ones
                        val newStores = if (options.extensionStores) desktopBackups.newExtensionStores(backup) else emptyList()
                        val chosen = when {
                            newStores.isEmpty() -> options
                            else -> when (storePrompt.confirm(newStores.map { "${it.name}: ${it.indexUrl}" })) {
                                true -> options
                                false -> options.copy(extensionStores = false)
                                null -> {
                                    result = RestoreOutcome.CANCELLED
                                    return@launch
                                }
                            }
                        }
                        val errors = desktopBackups.restore(backup, chosen) { _, progress, total ->
                            _status.value = localized(DMR.strings.restoring_of, file.name, progress, total)
                        }
                        result = RestoreOutcome.OK
                        Toasts.show(
                            if (errors.isEmpty()) {
                                localized(DMR.strings.restored_entries, backup.backupManga.size)
                            } else {
                                localized(DMR.strings.restored_entries_with_errors, backup.backupManga.size, errors.size)
                            },
                        )
                    } catch (e: Exception) {
                        Toasts.error(localized(DMR.strings.restore_failed), e)
                    } finally {
                        _status.value = null
                    }
                }
            } finally {
                outcome.complete(result)
            }
        }
        return outcome
    }
}
