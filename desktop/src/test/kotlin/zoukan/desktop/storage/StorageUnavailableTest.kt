package zoukan.desktop.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.backup.service.BackupPreferences
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.backup.AutoBackup
import zoukan.desktop.local.LocalSource
import java.io.File

class StorageUnavailableTest {

    @TempDir
    lateinit var temp: File

    private fun storage(chosen: String): ZoukanStorage {
        val store = InMemoryPreferenceStore(
            sequenceOf(InMemoryPreferenceStore.InMemoryPreference(Preference.appStateKey("storage_dir"), chosen, "")),
        )
        return ZoukanStorage(ZoukanDirs(File(temp, "data")), store)
    }

    private fun <T> pref(value: T) = mockk<Preference<T>>(relaxed = true) { every { get() } returns value }

    @Test
    fun `the data folder and an existing chosen folder are available`() {
        storage("").isAvailable shouldBe true
        storage("content://com.android.externalstorage/tree/primary").isAvailable shouldBe true
        val chosen = File(temp, "drive").apply { mkdirs() }
        storage(chosen.path).isAvailable shouldBe true
        storage(chosen.path).requireAvailable()
    }

    @Test
    fun `a missing chosen folder, or a file in its place, is not available`() {
        val missing = File(temp, "unplugged/Zoukan")
        storage(missing.path).isAvailable shouldBe false
        val file = File(temp, "file").apply { writeText("x") }
        storage(file.path).isAvailable shouldBe false

        val e = shouldThrow<StorageUnavailableException> { storage(missing.path).requireAvailable() }
        e.folder shouldBe missing
        e.message!!.contains(missing.path) shouldBe true
    }

    @Test
    fun `only folders under a missing storage folder are missing`() {
        val missing = File(temp, "unplugged/Zoukan")
        val storage = storage(missing.path)
        storage.isMissing(storage.downloads) shouldBe true
        storage.isMissing(storage.autoBackups) shouldBe true
        storage.isMissing(missing) shouldBe true
        storage.isMissing(File(temp, "data/backups")) shouldBe false
        storage.isMissing(File(temp, "unplugged/Zoukan2")) shouldBe false
        storage(File(temp, "drive").apply { mkdirs() }.path).isMissing(File(temp, "drive/downloads")) shouldBe false
    }

    @Test
    fun `an automatic backup creates nothing when the storage folder is missing`() = runBlocking<Unit> {
        val missing = File(temp, "unplugged/Zoukan")
        val timestamp = pref(0L)
        val preferences = mockk<BackupPreferences> {
            every { backupInterval() } returns pref(12)
            every { lastAutoBackupTimestamp() } returns timestamp
        }
        val written = pref("")

        AutoBackup(storage(missing.path), File(temp, "android"), preferences, written).run() shouldBe null

        File(temp, "unplugged").exists() shouldBe false
        verify(exactly = 0) { timestamp.set(any()) }
        verify(exactly = 0) { written.set(any()) }
    }

    @Test
    fun `the local source lists nothing and creates nothing when the storage folder is missing`() = runBlocking<Unit> {
        val missing = File(temp, "unplugged/Zoukan")
        val storage = storage(missing.path)
        val source = LocalSource(folder = { storage.local })

        source.getPopularManga(1).mangas shouldBe emptyList()
        source.getLatestUpdates(1).mangas shouldBe emptyList()
        source.mangaDir("Some entry") shouldBe null

        File(temp, "unplugged").exists() shouldBe false
    }
}
