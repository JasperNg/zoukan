package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.updater.AppUpdateJob
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import tachiyomi.core.common.i18n.stringResource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,
) {

    private val errors = mutableListOf<Pair<Date, String>>()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        restoreFromFile(uri, options)

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    // ZOUKAN -->
    // Restoring itself happens in the shared backup module, so the desktop app restores the same way
    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val backup = BackupDecoder(context).decode(uri)

        val contentRestorer = BackupContentRestorer(
            isSync = isSync,
            onProgress = { step, progress, total ->
                val title = when (step) {
                    is BackupContentRestorer.Step.Section -> context.stringResource(step.label)
                    is BackupContentRestorer.Step.Entry -> step.title
                }
                with(notifier) {
                    showRestoreProgress(title, progress, total, isSync)
                        // KMK -->
                        .show(Notifications.ID_RESTORE_PROGRESS)
                    // KMK <--
                }
            },
            preferenceRestorer = PreferenceRestorer(
                onAppPreferencesRestored = {
                    LibraryUpdateJob.setupTask(context)
                    BackupCreateJob.setupTask(context)
                    // KMK -->
                    AppUpdateJob.setupTask(context)
                    // KMK <--
                },
            ),
        )
        errors += contentRestorer.restore(backup, options)
    }
    // ZOUKAN <--

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("komikku_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (_: Exception) {
            // Empty
        }
        return File("")
    }
}
