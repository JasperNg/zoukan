package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.storage.ZoukanStorage
import zoukan.desktop.tray.Notifications
import zoukan.i18n.desktop.DMR
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Automatic backups into `<storage>/autobackup`, keeping the newest four like the Android app.
 * They are on by default (every 12 hours, Android's default), because the database key lives in the
 * OS secret store: if that is lost, a backup is the way back.
 */
class AutoBackup(
    private val storage: ZoukanStorage,
    private val androidDir: File,
    private val preferences: BackupPreferences,
) {
    /** When the last attempt failed; the next one waits [RETRY_AFTER], not the next minute's check. */
    @Volatile
    private var lastFailure = 0L

    fun isDue(now: Long = System.currentTimeMillis()): Boolean {
        val hours = preferences.backupInterval().get()
        if (hours <= 0) return false
        if (now - lastFailure < RETRY_AFTER) return false
        return now - preferences.lastAutoBackupTimestamp().get() >= hours * HOUR
    }

    /** Makes a backup now and removes the oldest ones over the limit. */
    suspend fun run(): File? {
        // A backup would snapshot the half-restored library and rotate the good ones out. The gate
        // is held for the whole run, so a restore asked for meanwhile waits for the backup to end
        var file: File? = null
        val ran = RestoreGate.unlessRestoring {
            file = backUp()
            true
        } ?: false
        if (!ran) logcat { "A backup restore is running; the automatic backup waits" }
        return file
    }

    private suspend fun backUp(): File? {
        return try {
            val folder = storage.autoBackups.apply { mkdirs() }
            val file = File(folder, DesktopBackups.defaultFileName())
            DesktopBackups(androidDir).create(file, BackupOptions())
            preferences.lastAutoBackupTimestamp().set(System.currentTimeMillis())
            prune(folder)
            logcat { "Automatic backup saved to $file" }
            lastFailure = 0L
            file
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastFailure = System.currentTimeMillis()
            logcat(LogPriority.ERROR, e) { "The automatic backup failed" }
            Notifications.post(localized(DMR.strings.automatic_backup_failed), e.message ?: e::class.simpleName.orEmpty(), Notifications.Kind.ERROR)
            null
        }
    }

    /** The newest automatic backup, for recovery: Zoukan's or the Android app's, by the time in its name. */
    fun latest(): File? = newest(storage.autoBackups)

    companion object {
        private const val HOUR = 60L * 60 * 1000
        const val MAX_AUTO_BACKUPS = 4
        const val RETRY_AFTER = HOUR
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")

        /** Any automatic backup, Zoukan's or the Android app's (`app.komikku_<time>.tachibk`). */
        private val BACKUP_FILE = Regex(""".+_(\d{4}-\d{2}-\d{2}_\d{2}-\d{2})\.tachibk""")

        /** Zoukan's own, as [DesktopBackups.defaultFileName] names them; the only ones it rotates. */
        private val ZOUKAN_BACKUP_FILE = Regex("""zoukan_(\d{4}-\d{2}-\d{2}_\d{2}-\d{2})\.tachibk""")

        /**
         * Deletes Zoukan's automatic backups in [folder] beyond the newest [MAX_AUTO_BACKUPS]. The
         * folder is shared with the Android app, which rotates its own: its files are never touched.
         */
        internal fun prune(folder: File) {
            backups(folder, ZOUKAN_BACKUP_FILE)
                .drop(MAX_AUTO_BACKUPS)
                .forEach { it.delete() }
        }

        /** The newest automatic backup in [folder], whichever app made it. */
        internal fun newest(folder: File): File? = backups(folder, BACKUP_FILE).firstOrNull()

        /**
         * The backups in [folder] whose name matches [pattern], newest first by the time in the
         * name. A name whose time does not parse is left out, so it is never rotated out by mistake.
         */
        private fun backups(folder: File, pattern: Regex): List<File> =
            folder.listFiles().orEmpty()
                .filter { it.isFile }
                .mapNotNull { file ->
                    val time = pattern.matchEntire(file.name)?.groupValues?.get(1) ?: return@mapNotNull null
                    val parsed = runCatching { LocalDateTime.parse(time, FILE_TIME) }.getOrNull() ?: return@mapNotNull null
                    file to parsed
                }
                .sortedWith(compareByDescending<Pair<File, LocalDateTime>> { it.second }.thenByDescending { it.first.lastModified() })
                .map { it.first }
    }
}
