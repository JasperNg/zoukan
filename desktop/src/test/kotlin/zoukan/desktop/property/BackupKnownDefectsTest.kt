package zoukan.desktop.property

import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.shouldBe
import okio.Buffer
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/** Round-trip changes [BackupPropertyTest] found, with the smallest backup found. Disabled until fixed. */
class BackupKnownDefectsTest {

    @Disabled(
        "Known defect: an empty custom genre list comes back as null, because protobuf writes no field for an empty " +
            "list (BackupManga.kt:62); MangaRestorer.kt:597 then drops custom info that had only that, so the " +
            "source's genres come back. The format is shared with Android, which does the same.",
    )
    @Test
    fun `an empty custom genre list survives write then read`() {
        val backup = Backup(listOf(BackupManga(source = 1, url = "/m/1", customGenre = emptyList())))
        val file = Buffer().also { BackupCodec.write(backup, it) }
        BackupCodec.decode(file).backupManga.single().customGenre shouldBe emptyList()
    }
}
