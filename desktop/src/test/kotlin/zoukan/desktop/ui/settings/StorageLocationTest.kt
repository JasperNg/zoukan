package zoukan.desktop.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.compat.localized
import zoukan.desktop.storage.ZoukanStorage
import zoukan.i18n.desktop.DMR
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalTestApi::class, ForTestsOnly::class)
class StorageLocationTest {

    @TempDir
    lateinit var temp: File

    @AfterEach
    fun waitForWriters() {
        JavaSharedPreferences.awaitPendingSaves()
    }

    @Test
    fun `a path that is not a writable folder is refused`() {
        val file = File(temp, "file.txt").apply { writeText("x") }
        StorageLocation.check(file) shouldBe StorageLocation.Verdict.NotWritable
        StorageLocation.check(File(file, "sub")) shouldBe StorageLocation.Verdict.NotWritable
    }

    @Test
    fun `a read-only folder is refused`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val folder = File(temp, "readonly").apply { mkdirs() }
        folder.setWritable(false)
        try {
            StorageLocation.check(folder) shouldBe StorageLocation.Verdict.NotWritable
        } finally {
            folder.setWritable(true)
        }
    }

    @Test
    fun `a folder that already holds downloads is flagged`() {
        File(temp, "other/downloads/Src/Title/ch.cbz").apply { parentFile.mkdirs() }.writeText("x")
        StorageLocation.check(File(temp, "other")) shouldBe StorageLocation.Verdict.Ok(hasDownloads = true)
        StorageLocation.check(File(temp, "empty").apply { mkdirs() }) shouldBe StorageLocation.Verdict.Ok(hasDownloads = false)
    }

    @Test
    fun `checking a folder leaves nothing behind`() {
        val folder = File(temp, "fresh").apply { mkdirs() }
        StorageLocation.check(folder)
        folder.listFiles().orEmpty().size shouldBe 0
    }

    @Test
    fun `changing the folder leaves the old downloads untouched`() {
        val dataDir = File(temp, "data")
        val storage = ZoukanStorage(
            ZoukanDirs(dataDir),
            DesktopPreferenceStore(JavaSharedPreferences("storage_location_test_${System.nanoTime()}")),
        )
        File(storage.downloads, "Src/Title/one.cbz").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        File(storage.downloads, "Src/Title/two.cbz").writeBytes(byteArrayOf(4, 5, 6, 7))
        File(storage.autoBackups, "backup.tachibk").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(8, 9))
        val before = hashes(dataDir)
        before.size shouldBe 3

        val newDir = File(temp, "new")
        var invalidated = false
        StorageLocation.apply(storage, newDir.path) { invalidated = true }

        invalidated shouldBe true
        storage.root shouldBe newDir
        hashes(dataDir) shouldBe before
        File(newDir, ZoukanStorage.DOWNLOADS).exists() shouldBe false
    }

    @Test
    fun `choosing a new folder says the downloads stay in the old one`() = runDesktopComposeUiTest {
        var confirmed = 0
        var dismissed = 0
        val old = File(temp, "old")
        setContent {
            StorageChangeDialog(old, File(temp, "new"), hasDownloads = true, onOpenOld = {}, onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
        }
        onNodeWithText(localized(DMR.strings.storage_change_warning, old.path)).assertExists()
        onNodeWithText(localized(DMR.strings.storage_folder_has_downloads)).assertExists()

        onNodeWithText(localized(MR.strings.action_cancel)).performClick()
        waitForIdle()
        dismissed shouldBe 1
        confirmed shouldBe 0

        onNodeWithText(localized(MR.strings.action_ok)).performClick()
        waitForIdle()
        confirmed shouldBe 1
    }

    @Test
    fun `iCloud Drive and other synced folders are flagged`() {
        val home = File("/Users/someone")
        StorageLocation.cloudSync(File(home, "Library/Mobile Documents/com~apple~CloudDocs/Zoukan"), home) shouldBe StorageLocation.CloudSync.SYNCED
        StorageLocation.cloudSync(File(home, "Library/Mobile Documents"), home) shouldBe StorageLocation.CloudSync.SYNCED
        StorageLocation.cloudSync(File(home, "Library/CloudStorage/Dropbox/Zoukan"), home) shouldBe StorageLocation.CloudSync.SYNCED
        // Case does not matter on macOS, nor a path that is not normalized
        StorageLocation.cloudSync(File(home, "library/mobile documents/x"), home) shouldBe StorageLocation.CloudSync.SYNCED
        StorageLocation.cloudSync(File(home, "Downloads/../Library/Mobile Documents/x"), home) shouldBe StorageLocation.CloudSync.SYNCED
    }

    @Test
    fun `Desktop and Documents may be synced`() {
        val home = File("/Users/someone")
        StorageLocation.cloudSync(File(home, "Documents/Zoukan"), home) shouldBe StorageLocation.CloudSync.MAYBE_SYNCED
        StorageLocation.cloudSync(File(home, "Desktop"), home) shouldBe StorageLocation.CloudSync.MAYBE_SYNCED
    }

    @Test
    fun `other folders are not flagged`() {
        val home = File("/Users/someone")
        StorageLocation.cloudSync(File(home, "Library/Application Support/Zoukan"), home) shouldBe null
        StorageLocation.cloudSync(File(home, "Documents2/Zoukan"), home) shouldBe null
        StorageLocation.cloudSync(File(home, "Manga"), home) shouldBe null
        StorageLocation.cloudSync(File("/Volumes/Drive/Documents"), home) shouldBe null
        StorageLocation.cloudSync(File("/Users/other/Documents"), home) shouldBe null
    }

    @Test
    fun `the change dialog warns about a synced folder`() = runDesktopComposeUiTest {
        setContent {
            StorageChangeDialog(
                File(temp, "old"),
                File(temp, "new"),
                hasDownloads = false,
                cloudSync = StorageLocation.CloudSync.MAYBE_SYNCED,
                onOpenOld = {},
                onConfirm = {},
                onDismiss = {},
            )
        }
        onNodeWithText(localized(DMR.strings.storage_folder_may_be_synced)).assertExists()
    }

    private fun hashes(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
    }
}
