package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import kotlinx.coroutines.runBlocking
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.secrets.SecretStore
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The backup round trip: restore a backup into a new, empty profile, back it up again with every
 * option (private settings included), read that file back, and compare the two backups field by
 * field with [BackupComparison].
 *
 * It starts the desktop runtime, which happens once per process, so it checks one file per run.
 */
object BackupCheck {

    data class Result(
        val file: File,
        val report: BackupComparison.Report,
        val restoreErrors: List<String>,
        val sizes: Map<String, Int>,
    ) {
        val passed: Boolean get() = report.passed && restoreErrors.isEmpty()
    }

    fun run(file: File, keepProfile: Boolean = false): Result {
        val profile = Files.createTempDirectory("zoukan-backup-check").toFile()
        // The throwaway profile keeps its database key in the profile, not in the OS store
        System.setProperty(SecretStore.FILE_STORE_PROPERTY, "true")
        try {
            val dirs = ZoukanDirs(profile)
            ZoukanRuntime.start(dirs, interactiveBrowser = false)
            val backups = DesktopBackups(dirs.android)

            val before = backups.read(file)
            val errors = runBlocking { backups.restore(before) }
            val exported = File(profile, "exported.tachibk")
            runBlocking { backups.create(exported, BackupOptions(privateSettings = true)) }
            val after = backups.read(exported)

            return Result(
                file = file,
                report = BackupComparison(before, after).compare(),
                restoreErrors = errors.map { it.second },
                sizes = BackupComparison.sizes(before),
            )
        } finally {
            if (keepProfile) println("Profile kept at $profile") else profile.deleteRecursively()
        }
    }

    fun markdown(result: Result): String = buildString {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        appendLine("# Backup round trip: ${result.file.name}")
        appendLine()
        appendLine("${Platform.id}, $time. Result: **${if (result.passed) "pass" else "FAIL"}**.")
        appendLine()
        appendLine("Restored into an empty profile, backed up again with every option, and compared field by field.")
        appendLine()
        appendLine("| Part | Items in the backup | Values compared |")
        appendLine("|---|---|---|")
        val parts = (result.sizes.keys + result.report.compared.keys).distinct()
        parts.forEach { part ->
            appendLine("| $part | ${result.sizes[part] ?: ""} | ${result.report.compared[part] ?: 0} |")
        }
        appendLine()
        if (result.restoreErrors.isNotEmpty()) {
            appendLine("## Restore errors")
            appendLine()
            result.restoreErrors.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine("## Differences")
        appendLine()
        if (result.report.differences.isEmpty()) {
            appendLine("None.")
        } else {
            result.report.differences.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("## Not compared")
        appendLine()
        result.report.notCompared.forEach { appendLine("- $it") }
    }
}
