package zoukan.desktop.property

import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.manga.EditManga

/**
 * Round-trip changes [BackupPropertyTest] found that the backup format (shared with the Android
 * app, so its field numbers and types cannot change) cannot avoid, pinned so that a change to them
 * is noticed. `desktop/PARITY.md` (the "Backups (.tachibk)" row) describes each.
 */
class BackupFormatLimitsTest {

    /**
     * Protobuf writes no field for an empty list, and `customGenre` (805) defaults to null, so an
     * empty custom genre list is read back as no custom genre. Neither app saves an empty one (the
     * next test), so a backup either app wrote loses nothing.
     */
    @Test
    fun `an empty custom genre list is read back as no custom genre`() {
        val backup = Backup(listOf(BackupManga(source = 1, url = "/m/1", customGenre = emptyList())))
        val file = Buffer().also { BackupCodec.write(backup, it) }
        BackupCodec.decode(file).backupManga.single().customGenre.shouldBeNull()
    }

    @Test
    fun `a non-empty custom genre list survives write then read`() {
        val backup = Backup(listOf(BackupManga(source = 1, url = "/m/1", customGenre = listOf("Custom"))))
        val file = Buffer().also { BackupCodec.write(backup, it) }
        BackupCodec.decode(file).backupManga.single().customGenre shouldBe listOf("Custom")
    }

    /** As Android's MangaScreenModel.updateMangaInfo: clearing every genre puts back the source's. */
    @Test
    fun `clearing every genre in the editor saves no custom genre`() = runBlocking {
        val saved = slot<CustomMangaInfo>()
        val setCustomMangaInfo = mockk<SetCustomMangaInfo> { every { set(capture(saved)) } returns Unit }
        val editManga = EditManga(setCustomMangaInfo, mockk { coEvery { await(any()) } returns true }, mockk())
        val manga = Manga.create().copy(id = 1, source = 1, url = "/m/1", ogTitle = "Title", ogGenre = listOf("Action"))

        editManga.saveInfo(manga, EditManga.Info("Title", "", "", "", genres = emptyList(), status = 0))

        saved.captured.genre.shouldBeNull()
    }
}
