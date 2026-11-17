package zoukan.desktop

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DataFolderPrivacyTest {
    @TempDir
    lateinit var temp: File

    private val notes = mutableListOf<String>()

    private fun resolve(move: ((java.nio.file.Path, java.nio.file.Path) -> Unit)? = null): File =
        if (move == null) {
            DataFolderPrivacy.resolve(temp, "logs", "logs.noindex", notes::add)
        } else {
            DataFolderPrivacy.resolve(temp, "logs", "logs.noindex", notes::add, move)
        }

    @Test
    fun `a fresh profile gets the new name`() {
        resolve() shouldBe File(temp, "logs.noindex")
        File(temp, "logs").exists() shouldBe false
        notes.shouldBeEmpty()
    }

    @Test
    fun `an old profile has the folder renamed with its files`() {
        File(temp, "logs").mkdirs()
        File(temp, "logs/zoukan.log").writeText("kept")

        resolve() shouldBe File(temp, "logs.noindex")

        File(temp, "logs").exists() shouldBe false
        File(temp, "logs.noindex/zoukan.log").readText() shouldBe "kept"
        notes.shouldBeEmpty()
    }

    @Test
    fun `when both exist the new one wins and the old one is left as it is`() {
        File(temp, "logs").mkdirs()
        File(temp, "logs/old.log").writeText("old")
        File(temp, "logs.noindex").mkdirs()
        File(temp, "logs.noindex/new.log").writeText("new")

        resolve() shouldBe File(temp, "logs.noindex")

        File(temp, "logs/old.log").readText() shouldBe "old"
        File(temp, "logs.noindex/new.log").readText() shouldBe "new"
        File(temp, "logs.noindex/old.log").exists() shouldBe false
    }

    @Test
    fun `a rename that fails keeps the old folder for this run without throwing`() {
        File(temp, "logs").mkdirs()
        File(temp, "logs/zoukan.log").writeText("kept")

        resolve { _, _ -> throw IOException("busy") } shouldBe File(temp, "logs")

        File(temp, "logs/zoukan.log").readText() shouldBe "kept"
        File(temp, "logs.noindex").exists() shouldBe false
        notes shouldHaveSize 1
        notes.single() shouldContain "busy"
    }

    @Test
    fun `the disposable folders take the noindex names only where they are hidden from Spotlight`() {
        val mac = ZoukanDirs(temp, hidesFromSpotlight = true)
        mac.logs shouldBe File(temp, "logs.noindex")
        mac.crashes shouldBe File(temp, "crashes.noindex")
        mac.reports shouldBe File(temp, "reports.noindex")
        mac.images shouldBe File(temp, "cache/images.noindex")
        mac.archives shouldBe File(temp, "cache/archives.noindex")
        mac.javacppCache shouldBe File(temp, "cache/javacpp.noindex")
        mac.androidCache shouldBe File(temp, "android/android-compat/appdata/cache.noindex")
        // User data and the browser keep their names
        mac.covers shouldBe File(temp, "cache/covers")
        mac.browserCache shouldBe File(temp, "cache/browser")
        mac.updates shouldBe File(temp, "updates")

        val other = ZoukanDirs(temp, hidesFromSpotlight = false)
        other.logs shouldBe File(temp, "logs")
        other.images shouldBe File(temp, "cache/images")
        other.androidCache shouldBe File(temp, "android/android-compat/appdata/cache")
    }

    @Test
    fun `an old profile's folders are renamed on first use`() {
        File(temp, "cache/images").mkdirs()
        File(temp, "cache/images/entry").writeText("x")

        ZoukanDirs(temp, hidesFromSpotlight = true).images shouldBe File(temp, "cache/images.noindex")
        File(temp, "cache/images.noindex/entry").readText() shouldBe "x"
    }

    @Test
    fun `the android cache setting is only written where the folder is renamed`() {
        ZoukanRuntime.androidCacheSetting(ZoukanDirs(temp, hidesFromSpotlight = false)) shouldBe ""
        val line = ZoukanRuntime.androidCacheSetting(ZoukanDirs(temp, hidesFromSpotlight = true))
        line shouldBe "android.files.cacheDir = \"${File(temp, "android/android-compat/appdata/cache.noindex").absolutePath.replace("\\", "\\\\")}\""
    }

    @Test
    fun `the backup exclusion is off on other systems and never throws`() {
        BackupExclusion.exclude(temp, notes::add, enabled = false) shouldBe false
        BackupExclusion.isExcluded(temp, enabled = false) shouldBe false
        notes.shouldBeEmpty()
    }

    @Test
    fun `the backup exclusion on a missing folder is noted, not thrown`() {
        assumeTrue(Platform.current == Platform.MACOS)
        BackupExclusion.exclude(File(temp, "missing"), notes::add) shouldBe false
        notes shouldHaveSize 1
    }

    @Test
    fun `the backup exclusion is set once as tmutil writes it`() {
        assumeTrue(Platform.current == Platform.MACOS)
        BackupExclusion.isExcluded(temp) shouldBe false
        BackupExclusion.exclude(temp, notes::add) shouldBe true
        BackupExclusion.isExcluded(temp) shouldBe true
        // Already set: nothing to do
        BackupExclusion.exclude(temp, notes::add) shouldBe true
        notes.shouldBeEmpty()

        val printed = ProcessBuilder("/usr/bin/xattr", "-px", BackupExclusion.ATTRIBUTE, temp.absolutePath)
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        printed.filterNot { it.isWhitespace() } shouldBe TMUTIL_VALUE
    }

    @Test
    fun `the exclusion value is the bytes tmutil addexclusion writes`() {
        BackupExclusion.VALUE.joinToString("") { "%02X".format(it) } shouldBe TMUTIL_VALUE
    }

    private fun assumePosix() = assumeTrue("posix" in temp.toPath().fileSystem.supportedFileAttributeViews())

    private fun permissions(dir: File): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath()))

    /** Runs [block] with the data folder override set to [dir]. */
    private fun <T> withOverride(dir: File, block: () -> T): T {
        System.setProperty("zoukan.dataDir", dir.path)
        return try {
            block()
        } finally {
            System.clearProperty("zoukan.dataDir")
        }
    }

    @Test
    fun `protect makes the default data folder private to the user`() {
        assumePosix()
        Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))

        DataFolderPrivacy.protect(ZoukanDirs(temp, hidesFromSpotlight = false, isDefaultRoot = true), notes::add)

        permissions(temp) shouldBe "rwx------"
        notes.shouldBeEmpty()
    }

    @Test
    fun `an existing data folder the user chose keeps its permissions`() {
        assumePosix()
        val chosen = File(temp, "chosen").apply { mkdirs() }
        Files.setPosixFilePermissions(chosen.toPath(), PosixFilePermissions.fromString("rwxr-x---"))

        val dirs = withOverride(chosen) { ZoukanDirs.default() }
        dirs.isDefaultRoot shouldBe false
        DataFolderPrivacy.protect(dirs.create(), notes::add)

        permissions(chosen) shouldBe "rwxr-x---"
        notes.shouldBeEmpty()
    }

    @Test
    fun `a data folder passed in that already exists keeps its permissions`() {
        assumePosix()
        Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rwxr-x---"))

        DataFolderPrivacy.protect(ZoukanDirs(temp, hidesFromSpotlight = false).create(), notes::add)

        permissions(temp) shouldBe "rwxr-x---"
    }

    @Test
    fun `a chosen data folder that Zoukan creates is made private`() {
        assumePosix()
        val chosen = File(temp, "new-profile")

        val dirs = withOverride(chosen) { ZoukanDirs.default() }
        // The one-instance lock creates the folder before the runtime starts
        chosen.mkdirs()
        Files.setPosixFilePermissions(chosen.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        DataFolderPrivacy.protect(dirs.create(), notes::add)

        permissions(chosen) shouldBe "rwx------"
        notes.shouldBeEmpty()
    }

    @Test
    fun `a data folder passed in that create makes is made private`() {
        assumePosix()
        val root = File(temp, "made")

        DataFolderPrivacy.protect(ZoukanDirs(root, hidesFromSpotlight = false).create(), notes::add)

        permissions(root) shouldBe "rwx------"
    }

    @Test
    fun `the harness profile beside a chosen data folder is made private only when created`() {
        val data = File(temp, "data")
        withOverride(data) { ZoukanDirs.harness() }.isDefaultRoot shouldBe false
        withOverride(data) { ZoukanDirs.harness() }.rootIsNew shouldBe true
        File(temp, "data-harness").mkdirs()
        // The first look in this process is remembered
        withOverride(data) { ZoukanDirs.harness() }.rootIsNew shouldBe true

        val existing = File(temp, "kept")
        File(temp, "kept-harness").mkdirs()
        withOverride(existing) { ZoukanDirs.harness() }.rootIsNew shouldBe false
    }

    @Test
    fun `a symbolic link to the data folder is not followed`() {
        assumePosix()
        val target = File(temp, "target").apply { mkdirs() }
        Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rwxr-x---"))
        val link = File(temp, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        DataFolderPrivacy.protect(ZoukanDirs(link, hidesFromSpotlight = false, isDefaultRoot = true), notes::add)

        permissions(target) shouldBe "rwxr-x---"
    }

    @Test
    fun `protect creates updates and keeps it out of Time Machine on macOS`() {
        assumeTrue(Platform.current == Platform.MACOS)
        val dirs = ZoukanDirs(temp, hidesFromSpotlight = true)
        File(temp, "updates").exists() shouldBe false

        DataFolderPrivacy.protect(dirs, notes::add)

        dirs.updates.isDirectory shouldBe true
        BackupExclusion.isExcluded(dirs.updates) shouldBe true
        dirs.disposableFolders.forEach { BackupExclusion.isExcluded(it) shouldBe true }
        notes.shouldBeEmpty()
    }

    @Test
    fun `protect does not create updates on other systems`() {
        DataFolderPrivacy.protect(ZoukanDirs(temp, hidesFromSpotlight = false), notes::add)
        File(temp, "updates").exists() shouldBe false
    }

    @Test
    fun `protect never throws on a data folder that is a file`() {
        val file = File(temp, "not-a-folder").apply { writeText("") }
        DataFolderPrivacy.protect(ZoukanDirs(file, hidesFromSpotlight = true), notes::add)
    }

    private companion object {
        /** `xattr -px com.apple.metadata:com_apple_backup_excludeItem` after `tmutil addexclusion` */
        const val TMUTIL_VALUE =
            "62706C69737430305F1011636F6D2E6170706C652E6261636B757064080000000000000101" +
                "00000000000000010000000000000000000000000000001C"
    }
}
