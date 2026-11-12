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
    suspend fun run(): File? = try {
        val folder = storage.autoBackups.apply { mkdirs() }
        val file = File(folder, DesktopBackups.defaultFileName())
        DesktopBackups(androidDir).create(file, BackupOptions())
        preferences.lastAutoBackupTimestamp().set(System.currentTimeMillis())
        folder.listFiles { f -> BACKUP_FILE.matches(f.name) }
            .orEmpty()
            .sortedByDescending { it.name }
            .drop(MAX_AUTO_BACKUPS)
            .forEach { it.delete() }
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

    /** The newest automatic backup, for recovery. */
    fun latest(): File? = storage.autoBackups.listFiles { f -> BACKUP_FILE.matches(f.name) }.orEmpty().maxByOrNull { it.name }

    companion object {
        private const val HOUR = 60L * 60 * 1000
        const val MAX_AUTO_BACKUPS = 4
        const val RETRY_AFTER = HOUR
        private val BACKUP_FILE = Regex(""".*_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}\.tachibk""")
    }
}
