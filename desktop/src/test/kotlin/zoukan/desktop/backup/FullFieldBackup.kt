package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.models.Backup

/**
 * The backup with every optional field of the format set: [SyntheticBackup] (which the
 * `zoukan backup synthetic` command also writes) plus the fields it leaves at their defaults, a
 * chapter's `lastModifiedAt` and `version`. Built from the shared backup model classes, so it stays
 * in step with the format; `SyntheticBackupCoverageTest` fails when a field is added to the models
 * and set in neither.
 */
object FullFieldBackup {
    fun create(legacy: Boolean = false): Backup = SyntheticBackup.create(legacy).apply {
        backupManga.flatMap { it.chapters }.forEachIndexed { i, chapter ->
            if (chapter.lastModifiedAt == 0L) chapter.lastModifiedAt = 1_700_010_000L + i
            if (chapter.version == 0L) chapter.version = i + 1L
        }
    }
}
