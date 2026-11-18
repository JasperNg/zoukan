package zoukan.desktop.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.testing.FileSystemSandbox
import java.io.File
import java.nio.file.Files

/** What the browser profile cleanup deletes, and above all what it keeps. */
class BrowserProfileCleanupTest {
    private val records = listOf(
        "History", "History-journal", "Top Sites", "Top Sites-journal", "Favicons", "Favicons-journal",
        "Visited Links", "Network Action Predictor", "Network Action Predictor-journal", "Shortcuts",
        "Shortcuts-journal", "chrome_debug.log",
    )

    private val kept = listOf(
        "Cookies", "Cookies-journal", "Local State", "Preferences", "Login Data", "Web Data",
        "Network Persistent State", "TransportSecurity", "Trust Tokens",
    )

    private fun File.file(name: String) = File(this, name).apply {
        parentFile.mkdirs()
        writeText("x")
    }

    @Test
    fun `deletes history, caches and the log in the profile and its Default folder`(@TempDir root: File) {
        for (folder in listOf(root, File(root, "Default"))) {
            records.forEach { folder.file(it) }
            folder.file("Cache/Cache_Data/data_0")
            folder.file("Code Cache/js/index")
            folder.file("Network/Reporting and NEL")
            folder.file("Network/Reporting and NEL-journal")
        }

        BrowserProfileCleanup.clean(root)

        for (folder in listOf(root, File(root, "Default"))) {
            records.forEach { File(folder, it).exists() shouldBe false }
            File(folder, "Cache").exists() shouldBe false
            File(folder, "Code Cache").exists() shouldBe false
            File(folder, "Network/Reporting and NEL").exists() shouldBe false
            File(folder, "Network/Reporting and NEL-journal").exists() shouldBe false
        }
    }

    @Test
    fun `keeps cookies, site storage and Local State`(@TempDir root: File) {
        for (folder in listOf(root, File(root, "Default"))) {
            kept.forEach { folder.file(it) }
            folder.file("Network/Cookies")
            folder.file("Local Storage/leveldb/000003.log")
            folder.file("IndexedDB/https_example.org_0.indexeddb.leveldb/CURRENT")
            folder.file("Session Storage/CURRENT")
        }
        // Named like a record, but not in a folder the cleanup looks at
        root.file("Extension State/History")

        BrowserProfileCleanup.clean(root)

        for (folder in listOf(root, File(root, "Default"))) {
            kept.forEach { File(folder, it).exists() shouldBe true }
            File(folder, "Network/Cookies").exists() shouldBe true
            File(folder, "Local Storage/leveldb/000003.log").exists() shouldBe true
            File(folder, "IndexedDB/https_example.org_0.indexeddb.leveldb/CURRENT").exists() shouldBe true
            File(folder, "Session Storage/CURRENT").exists() shouldBe true
        }
        File(root, "Extension State/History").exists() shouldBe true
    }

    @Test
    fun `a folder named like a record file is kept, and a file named like a cache folder too`(@TempDir root: File) {
        root.file("History/keep")
        root.file("Cache")

        BrowserProfileCleanup.clean(root)

        File(root, "History/keep").exists() shouldBe true
        File(root, "Cache").exists() shouldBe true
    }

    @Test
    fun `does not follow a link out of the profile`(@TempDir dir: File) {
        val outside = File(dir, "outside").apply { mkdirs() }
        outside.file("History")
        outside.file("data")
        val root = File(dir, "browser").apply { mkdirs() }
        val linked = runCatching {
            Files.createSymbolicLink(File(root, "Cache").toPath(), outside.toPath())
            Files.createSymbolicLink(File(root, "Default").toPath(), outside.toPath())
        }.isSuccess
        if (!linked) return // Windows without the right to make links

        BrowserProfileCleanup.clean(root)

        File(outside, "History").exists() shouldBe true
        File(outside, "data").exists() shouldBe true
    }

    @Test
    fun `a missing profile is not an error`(@TempDir dir: File) {
        BrowserProfileCleanup.clean(File(dir, "missing")) shouldBe 0
    }

    @Test
    fun `records are matched by name`() {
        BrowserProfileCleanup.isRecord("History-journal") shouldBe true
        BrowserProfileCleanup.isRecord("Reporting and NEL") shouldBe true
        BrowserProfileCleanup.isRecord("Cookies") shouldBe false
        BrowserProfileCleanup.isRecord("Local State") shouldBe false
    }

    @Test
    fun `clearing all browser data empties the profile and follows no link in it`(@TempDir temp: File) {
        FileSystemSandbox.create(temp).use { sandbox ->
            val profile = File(sandbox.dataFolder, "cache/browser-profile")
            profile.file("Default/Local Storage/leveldb/000003.log")
            profile.file("Cache/Cache_Data/data_0")
            profile.file("Local State")
            // Links in the profile to the user's folder and to one of the user's files
            val folderLink = sandbox.link(File(profile, "Default/linked"), sandbox.userFolder)
            assumeTrue(folderLink != null, "symbolic links cannot be made here")
            sandbox.link(File(profile, "linked-file"), File(sandbox.userFolder, "notes.txt"))
            sandbox.record()

            BrowserProfileCleanup.clearAll(profile)

            profile.isDirectory shouldBe true
            profile.listFiles().orEmpty().toList() shouldBe emptyList()
            sandbox.assertNothingOutsideAppChanged()
        }
    }

    @Test
    fun `clearing all browser data leaves a profile folder that is a link alone`(@TempDir temp: File) {
        FileSystemSandbox.create(temp).use { sandbox ->
            val profile = File(sandbox.dataFolder, "cache/browser-profile")
            assumeTrue(sandbox.link(profile, sandbox.userFolder) != null, "symbolic links cannot be made here")
            sandbox.record()

            BrowserProfileCleanup.clearAll(profile)

            Files.isSymbolicLink(profile.toPath()) shouldBe true
            sandbox.assertNothingOutsideAppChanged()
        }
    }

    @Test
    fun `clearing all browser data of a missing profile is not an error`(@TempDir temp: File) {
        BrowserProfileCleanup.clearAll(File(temp, "absent"))
        File(temp, "absent").exists() shouldBe false
    }
}
