package zoukan.desktop.testing

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.io.TempDir
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserDefinedFileAttributeView

class FreshInjektTest {

    private class Sentinel(val name: String)

    @Test
    fun `FreshInjekt isolates Injekt mutations and restores the previous scope`() {
        val outer = Injekt
        val ext = FreshInjekt()
        val ctx = io.mockk.mockk<ExtensionContext>(relaxed = true)
        ext.beforeEach(ctx)
        try {
            Injekt.addSingleton(Sentinel("scoped"))
            Injekt.get<Sentinel>().name shouldBe "scoped"
        } finally {
            ext.afterEach(ctx)
        }
        (Injekt === outer) shouldBe true
        runCatching { Injekt.get<Sentinel>() }.isFailure shouldBe true
    }

    @Test
    fun `test classes that mutate global Injekt use FreshInjekt and do not register in init`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val injektMutators = listOf(
            "desktop/src/test/kotlin/zoukan/desktop/ui/manga/EditMangaDialogSaveTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/ui/manga/EntryActionsDialogsTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/compat/ContextStubsTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/network/DohClientsTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/sync/SyncManagerTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/AnilistApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/MangaDexFollowApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/KavitaApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/SuwayomiApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/BangumiApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/ShikimoriApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/MyAnimeListApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/MangaUpdatesApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/KitsuApiTest.kt",
        )
        for (path in injektMutators) {
            val text = File(root, path).readText()
            text shouldContain "FreshInjekt()"
            text shouldNotContain "TrackerTestEnvironment().close"
        }
        val recordedHttp = File(root, "desktop/src/test/kotlin/zoukan/desktop/track/RecordedHttp.kt").readText()
        recordedHttp shouldNotContain "Injekt = InjektScope"

        for (path in listOf(
            "desktop/src/test/kotlin/zoukan/desktop/library/LibraryUpdaterBehaviorTest.kt",
        )) {
            val text = File(root, path).readText()
            text shouldContain "@TempDir"
            text shouldNotContain "Files.createTempDirectory"
        }
    }
}

/**
 * Tests must never read or write the developer's real home folder. The build points `user.home` at
 * a folder under build/, but a test run from an IDE has the real one, so test code reaches the home
 * folder only through [TestHome] (and makes files there with [FileSystemSandbox]). Fails when a
 * test source reads the home folder another way.
 */
class HomeFolderScanTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** Ways to reach the home folder: the system property, the environment, library helpers, `~`. */
    private val homeReads = listOf(
        Regex(""""user\.home""""),
        Regex("""\bgetenv\(\s*"(HOME|USERPROFILE|HOMEDRIVE|HOMEPATH)"\s*\)"""),
        Regex("""\bFileUtils\.getUserDirectory(Path)?\b"""),
        Regex("""\bSystemUtils\.(USER_HOME|getUserHome)\b"""),
        Regex("""\b(expandHome|expandTilde|untildify)\b"""),
        // A path that starts with ~, which some helper must expand to the home folder
        Regex(""""~[/\\]"""),
    )

    /** Files allowed to read the home folder directly, each with why. */
    private val allowlist = mapOf(
        "desktop/src/test/kotlin/zoukan/desktop/testing/TestingHarness.kt" to
            "the one place tests read user.home: it checks the build set it, else uses the temporary folder",
        "desktop/src/test/kotlin/zoukan/desktop/testing/TestingHarnessTest.kt" to
            "this scan's own patterns",
    )

    @Test
    fun `test code reads the home folder only through TestHome`() {
        val tests = File(root, "desktop/src/test")
        check(tests.isDirectory) { "Not run from the zoukan checkout: $root" }
        val violations = tests.walk()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.relativeTo(root).invariantSeparatorsPath !in allowlist }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filterNot { (_, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                    .filter { (_, line) -> homeReads.any { it.containsMatchIn(line) } }
                    .map { (i, line) -> "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1}: ${line.trim()}" }
            }
            .toList()
        check(violations.isEmpty()) {
            "Read the home folder with TestHome.root() (or make files with FileSystemSandbox), not directly:\n" +
                violations.joinToString("\n")
        }
    }

    @Test
    fun `every allowlisted file still exists`() {
        val missing = allowlist.keys.filterNot { File(root, it).isFile }
        check(missing.isEmpty()) { "Allowlist entries for files that are gone: $missing" }
    }

    @Test
    fun `the patterns catch each way of reading the home folder`() {
        val samples = listOf(
            """val home = System.getProperty("user.home")""",
            """File(System.getenv("HOME"), "x")""",
            """System.getenv("USERPROFILE")""",
            """FileUtils.getUserDirectory()""",
            """SystemUtils.USER_HOME""",
            """val p = expandHome(path)""",
            """File("~/Downloads")""",
        )
        samples.filterNot { sample -> homeReads.any { it.containsMatchIn(sample) } }.let { missed ->
            check(missed.isEmpty()) { "Not caught: $missed" }
        }
        // A home folder in a fixed test value is not a read
        listOf("""ZoukanDirs.default(platform, null, { null }, "/home/someone")""", """TestHome.root()""")
            .filter { sample -> homeReads.any { it.containsMatchIn(sample) } }
            .let { flagged -> check(flagged.isEmpty()) { "Wrongly caught: $flagged" } }
    }
}

class TestHomeTest {
    /** The project's build folder, where the build puts the test JVMs' home and data folders. */
    private val testHomes = File(System.getProperty("user.dir"), "build/test-home").absoluteFile

    @Test
    fun `a test JVM the build starts has a home and a data folder under the build folder`() {
        // Gradle names its test workers; a run from an IDE may not have the build's properties
        assumeTrue(System.getProperty("org.gradle.test.worker") != null, "not started by Gradle")
        TestHome.isSandboxed() shouldBe true
        TestHome.root().startsWith(testHomes) shouldBe true
        ZoukanDirs.default().root.startsWith(testHomes) shouldBe true
        ZoukanDirs.harness().root.startsWith(testHomes) shouldBe true
    }

    @Test
    fun `the home folder is never the real one, whatever the properties say`() {
        TestHome.withSystemProperty(TestHome.MARKER, null) {
            TestHome.isSandboxed() shouldBe false
            TestHome.root() shouldBe File(System.getProperty("java.io.tmpdir"), "zoukan-test-home").absoluteFile
        }
    }

    @Test
    fun `a property set for one block gets its old value back, or none`() {
        val key = "zoukan.test.property"
        TestHome.withSystemProperty(key, "outer") {
            TestHome.withSystemProperty(key, "inner") { System.getProperty(key) shouldBe "inner" }
            System.getProperty(key) shouldBe "outer"
            TestHome.withSystemProperty(key, null) { System.getProperty(key) shouldBe null }
            System.getProperty(key) shouldBe "outer"
        }
        System.getProperty(key) shouldBe null
    }
}

/** The sandbox's own checks: it sees every kind of change outside the app's folders, and only those. */
class FileSystemSandboxTest {
    @TempDir
    lateinit var temp: File

    private val sandbox by lazy { FileSystemSandbox.create(temp) }

    @AfterEach
    fun close() = sandbox.close()

    private fun failure(): String = assertThrows<IllegalStateException> { sandbox.assertNothingOutsideAppChanged() }.message!!

    private fun posix() = "posix" in sandbox.home.toPath().fileSystem.supportedFileAttributeViews()

    @Test
    fun `a new sandbox has every kind of file and reports no change`() {
        sandbox.dataFolder.isDirectory shouldBe true
        sandbox.storageZoukanFolders.forEach { it.isDirectory shouldBe true }
        File(sandbox.userFolder, "notes.txt").isFile shouldBe true
        File(sandbox.downloads, "invoice.pdf").isFile shouldBe true
        sandbox.otherProgramCrashFile.readText() shouldContain "org.example.OtherApp"
        File(sandbox.readOnlyFolder, FileSystemSandbox.READ_ONLY_FILE).isFile shouldBe true
        sandbox.linkFromApp?.let { Files.readSymbolicLink(it.toPath()).toFile() shouldBe sandbox.linkTarget }
        sandbox.home.startsWith(temp.canonicalFile) shouldBe true
        sandbox.assertNothingOutsideAppChanged()
    }

    @Test
    fun `the read-only folder cannot be written`() {
        assumeTrue(posix())
        // A user that ignores permissions (root) would see no difference
        assumeTrue(System.getProperty("user.name") != "root")
        sandbox.readOnlyFolder.canWrite() shouldBe false
        runCatching { File(sandbox.readOnlyFolder, "new").createNewFile() }.getOrDefault(false) shouldBe false
        File(sandbox.readOnlyFolder, FileSystemSandbox.READ_ONLY_FILE).canWrite() shouldBe false
    }

    @Test
    fun `changes in the app's folders are allowed`() {
        File(sandbox.dataFolder, "zoukan.db").writeText("changed")
        File(sandbox.dataFolder, "logs/new.log").apply { parentFile.mkdirs() }.writeText("log")
        File(sandbox.storageFolder, "downloads/Source/Manga/Chapter.cbz").delete()
        File(sandbox.storageFolder, "autobackup/backup.tachibk").writeText("backup")
        sandbox.linkFromApp?.delete()
        if (posix()) Files.setPosixFilePermissions(sandbox.dataFolder.toPath(), PosixFilePermissions.fromString("rwx------"))

        sandbox.changesOutsideApp().shouldBeEmpty()
        sandbox.assertNothingOutsideAppChanged()
    }

    @Test
    fun `a changed, deleted or new file outside the app's folders fails with each path listed`() {
        File(sandbox.userFolder, "notes.txt").writeText("overwritten")
        sandbox.otherProgramCrashFile.delete()
        File(sandbox.downloads, "Zoukan").mkdirs()

        val message = failure()
        message shouldContain "changed content: Documents/Chosen/notes.txt"
        message shouldContain "deleted file tmp/hs_err_pid${FileSystemSandbox.OTHER_PID}.log"
        message shouldContain "created folder Downloads/Zoukan"
        sandbox.changesOutsideApp().size shouldBe 3
    }

    @Test
    fun `a new file in the storage folder itself fails, as it is not the app's`() {
        // Not a name of the app's folders in any case, so not one of them even on a case-insensitive volume
        File(sandbox.storageFolder, "Photos").mkdirs()
        failure() shouldContain "created folder Documents/Zoukan/Photos"
    }

    @Test
    fun `following the app's link out of its folder fails`() {
        val link = sandbox.linkFromApp
        assumeTrue(link != null, "symbolic links cannot be made here")
        File(link, "keep.txt").delete()
        File(link, "written-through-link").writeText("x")

        val message = failure()
        message shouldContain "deleted file Documents/Chosen/linked-from-app/keep.txt"
        message shouldContain "created file Documents/Chosen/linked-from-app/written-through-link"
    }

    @Test
    fun `a permission change outside the app's folders fails`() {
        if (posix()) {
            Files.setPosixFilePermissions(sandbox.userFolder.toPath(), PosixFilePermissions.fromString("rwx------"))
            failure() shouldContain "-> rwx------: Documents/Chosen"
        } else {
            File(sandbox.userFolder, "notes.txt").setReadOnly() shouldBe true
            failure() shouldContain "readOnly=true"
        }
    }

    @Test
    fun `a write that makes the read-only folder writable fails`() {
        assumeTrue(posix())
        Files.setPosixFilePermissions(sandbox.readOnlyFolder.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        failure() shouldContain "permissions r-xr-xr-x -> rwxr-xr-x: read-only"
    }

    @Test
    fun `an extended attribute added, changed or removed outside the app's folders fails`() {
        assumeTrue(sandbox.xattrsSupported, "no extended attributes on this file system")
        val view = Files.getFileAttributeView(sandbox.userFolder.toPath(), UserDefinedFileAttributeView::class.java)
        view.write(ATTRIBUTE, ByteBuffer.wrap("one".toByteArray()))
        failure() shouldContain "$ATTRIBUTE added: Documents/Chosen"

        sandbox.record()
        view.write(ATTRIBUTE, ByteBuffer.wrap("two".toByteArray()))
        failure() shouldContain "$ATTRIBUTE changed: Documents/Chosen"

        sandbox.record()
        view.delete(ATTRIBUTE)
        failure() shouldContain "$ATTRIBUTE removed: Documents/Chosen"

        // In the app's folders it is allowed
        sandbox.record()
        Files.getFileAttributeView(sandbox.dataFolder.toPath(), UserDefinedFileAttributeView::class.java)
            .write(ATTRIBUTE, ByteBuffer.wrap("ok".toByteArray()))
        sandbox.assertNothingOutsideAppChanged()
    }

    @Test
    fun `the Time Machine mark as tmutil sets it is seen on macOS`() {
        val xattr = File("/usr/bin/xattr")
        assumeTrue(xattr.canExecute() && sandbox.xattrsSupported)
        val process = ProcessBuilder(xattr.path, "-w", "com.apple.metadata:com_apple_backup_excludeItem", "com.apple.backupd", sandbox.downloads.path)
            .redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor() shouldBe 0
        failure() shouldContain "extended attribute com.apple.metadata:com_apple_backup_excludeItem added: Downloads"
    }

    @Test
    fun `a link outside the app's folders that points elsewhere fails`() {
        val link = sandbox.link(File(sandbox.userFolder, "shortcut"), sandbox.downloads)
        assumeTrue(link != null, "symbolic links cannot be made here")
        sandbox.record()
        link!!.delete()
        sandbox.link(link, sandbox.otherProgramFolder)

        failure() shouldContain "link target ${sandbox.downloads.path} -> ${sandbox.otherProgramFolder.path}: Documents/Chosen/shortcut"
    }

    @Test
    fun `a folder added as the app's may change`() {
        val extra = File(sandbox.userFolder, "photos")
        sandbox.addAppFolder(extra)
        File(extra, "holiday.jpg").delete()
        sandbox.assertNothingOutsideAppChanged()
        assertThrows<IllegalArgumentException> { sandbox.addAppFolder(temp.parentFile) }
    }

    @Test
    fun `closing deletes the sandbox, read-only folder too, and never what a link points to`() {
        val outside = File(temp, "outside").apply { mkdirs() }
        val kept = File(outside, "kept.txt").apply { writeText("kept") }
        val link = sandbox.link(File(sandbox.dataFolder, "to-outside"), outside)
        val home = sandbox.home

        sandbox.close()

        home.exists() shouldBe false
        kept.readText() shouldBe "kept"
        if (link != null) outside.list()!!.toList() shouldContain "kept.txt"
    }

    private companion object {
        /** A name the JDK accepts on every system (it adds the `user.` namespace on Linux and macOS). */
        const val ATTRIBUTE = "zoukan.test"
    }
}
