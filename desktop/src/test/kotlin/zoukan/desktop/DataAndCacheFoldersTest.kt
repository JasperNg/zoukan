package zoukan.desktop

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.testing.FileSystemSandbox
import zoukan.desktop.testing.TestHome
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions

class ZoukanDirsTest {
    private val home = "C:\\Users\\someone"
    private val windowsEnv = mapOf(
        "APPDATA" to "C:\\Users\\someone\\AppData\\Roaming",
        "LOCALAPPDATA" to "C:\\Users\\someone\\AppData\\Local",
    )

    /** The value the build set for every test, put back after each one. */
    private val previousDataDir: String? = System.getProperty("zoukan.dataDir")

    @AfterEach
    fun tearDown() {
        previousDataDir?.let { System.setProperty("zoukan.dataDir", it) } ?: System.clearProperty("zoukan.dataDir")
    }

    @Test
    fun `on Windows the caches leave the roaming data folder and the rest stays`() {
        val dirs = ZoukanDirs.default(Platform.WINDOWS, null, windowsEnv::get, home)
        val roaming = File(windowsEnv.getValue("APPDATA"), "Zoukan")
        val local = File(windowsEnv.getValue("LOCALAPPDATA"), "Zoukan-local")

        dirs.root shouldBe roaming
        dirs.localRoot shouldBe local
        dirs.hasLocalRoot shouldBe true
        dirs.browser shouldBe File(local, "browser")
        dirs.browserCache shouldBe File(local, "cache/browser")
        dirs.images shouldBe File(local, "cache/images")
        dirs.javacppCache shouldBe File(local, "cache/javacpp")
        dirs.archives shouldBe File(local, "cache/archives")
        dirs.tempFiles shouldBe File(local, "cache/tmp")
        // The user's data, and covers (custom covers are the user's own), stay in the data folder
        dirs.database shouldBe File(roaming, "zoukan.db")
        dirs.covers shouldBe File(roaming, "cache/covers")
        dirs.cookies shouldBe File(roaming, "cookies.json")
        dirs.backups shouldBe File(roaming, "backups")
        dirs.secrets shouldBe File(roaming, "secrets")
        dirs.logs shouldBe File(roaming, "logs")
    }

    @Test
    fun `the local folder is not the folder the per-user installer uses`() {
        val dirs = ZoukanDirs.default(Platform.WINDOWS, null, windowsEnv::get, home)
        (dirs.localRoot == File(windowsEnv.getValue("LOCALAPPDATA"), "Zoukan")) shouldBe false
        // Nor inside the harness profile, nor the other way round
        val harness = File(windowsEnv.getValue("LOCALAPPDATA"), "Zoukan\\harness").toPath()
        dirs.localRoot.toPath().startsWith(harness) shouldBe false
        harness.startsWith(dirs.localRoot.toPath()) shouldBe false
    }

    @Test
    fun `without the environment variables Windows falls back to the home folder`() {
        val dirs = ZoukanDirs.default(Platform.WINDOWS, null, { null }, home)
        dirs.root shouldBe File("$home\\AppData\\Roaming", "Zoukan")
        dirs.localRoot shouldBe File("$home\\AppData\\Local", "Zoukan-local")
    }

    @Test
    fun `a chosen data folder keeps its caches inside it`() {
        val byProperty = ZoukanDirs.default(Platform.WINDOWS, "D:\\Portable\\Zoukan", windowsEnv::get, home)
        byProperty.hasLocalRoot shouldBe false
        byProperty.browserCache shouldBe File(byProperty.root, "cache/browser")

        val byVariable = ZoukanDirs.default(Platform.WINDOWS, null, (windowsEnv + ("ZOUKAN_DATA_DIR" to "D:\\Z"))::get, home)
        byVariable.hasLocalRoot shouldBe false
        byVariable.browser shouldBe File(byVariable.root, "browser")
    }

    @Test
    fun `macOS and Linux keep everything in the data folder`() {
        for (platform in listOf(Platform.MACOS, Platform.LINUX)) {
            val dirs = ZoukanDirs.default(platform, null, { null }, "/home/someone")
            dirs.hasLocalRoot shouldBe false
            dirs.browserCache shouldBe File(dirs.root, "cache/browser")
            // On macOS the disposable caches take their `.noindex` names, in the data folder
            val archives = if (platform == Platform.MACOS) "cache/archives.noindex" else "cache/archives"
            dirs.archives shouldBe File(dirs.root, archives)
        }
    }

    @Test
    fun `only macOS gives the disposable folders the noindex names, whatever this computer runs`() {
        ZoukanDirs.default(Platform.MACOS, null, { null }, "/home/someone").hidesFromSpotlight shouldBe true
        ZoukanDirs.default(Platform.LINUX, null, { null }, "/home/someone").hidesFromSpotlight shouldBe false
        val windows = ZoukanDirs.default(Platform.WINDOWS, null, windowsEnv::get, home)
        windows.hidesFromSpotlight shouldBe false
        windows.images shouldBe File(windows.localRoot, "cache/images")
    }

    @Test
    fun `a cache kept in the data folder keeps the noindex name on macOS`() {
        val dirs = ZoukanDirs(File("/tmp/profile"), File("/tmp/profile-local"), hidesFromSpotlight = true)
            .keepingInRoot(setOf(ZoukanDirs.IMAGES))
        dirs.hidesFromSpotlight shouldBe true
        dirs.images shouldBe File("/tmp/profile", "cache/images.noindex")
        dirs.archives shouldBe File("/tmp/profile-local", "cache/archives.noindex")
        dirs.tempFiles shouldBe File("/tmp/profile-local", "cache/tmp.noindex")
    }

    @Test
    fun `a folder built from one root keeps everything in it, as the tests and the harness need`() {
        val dirs = ZoukanDirs(File("/tmp/profile"))
        dirs.hasLocalRoot shouldBe false
        dirs.browserCache shouldBe File("/tmp/profile", "cache/browser")
    }

    @Test
    fun `the harness profile keeps its caches inside it`() {
        System.setProperty("zoukan.dataDir", "/tmp/zoukan-data")
        ZoukanDirs.harness().hasLocalRoot shouldBe false
    }

    @Test
    fun `a cache that could not move is used in the data folder, the others in the local folder`() {
        val dirs = ZoukanDirs.default(Platform.WINDOWS, null, windowsEnv::get, home)
            .keepingInRoot(setOf(ZoukanDirs.BROWSER_PROFILE))
        dirs.browserCache shouldBe File(dirs.root, "cache/browser")
        dirs.browser shouldBe File(dirs.localRoot, "browser")
        dirs.images shouldBe File(dirs.localRoot, "cache/images")
    }
}

class DataFolderPrivacyTest {
    @TempDir
    lateinit var temp: File

    private val notes = mutableListOf<String>()

    private fun resolve(move: ((Path, Path) -> Unit)? = null): File =
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
        mac.tempFiles shouldBe File(temp, "cache/tmp.noindex")
        mac.javacppCache shouldBe File(temp, "cache/javacpp.noindex")
        mac.androidCache shouldBe File(temp, "android/android-compat/appdata/cache.noindex")
        // User data and the browser keep their names
        mac.covers shouldBe File(temp, "cache/covers")
        mac.browserCache shouldBe File(temp, "cache/browser")
        mac.updates shouldBe File(temp, "updates")

        val other = ZoukanDirs(temp, hidesFromSpotlight = false)
        other.logs shouldBe File(temp, "logs")
        other.images shouldBe File(temp, "cache/images")
        other.tempFiles shouldBe File(temp, "cache/tmp")
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
    private fun <T> withOverride(dir: File, block: () -> T): T = TestHome.withSystemProperty("zoukan.dataDir", dir.path, block)

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
    fun `a disposable folder that is a symbolic link is neither created nor marked`() {
        assumePosix()
        val target = File(temp, "elsewhere").apply { mkdirs() }
        val link = File(temp, "logs.noindex")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val dangling = File(temp, "crashes.noindex")
        Files.createSymbolicLink(dangling.toPath(), File(temp, "missing-target").toPath())
        val marked = mutableListOf<File>()
        val exclude: (File, (String) -> Unit) -> Boolean = { dir, _ ->
            marked += dir
            true
        }

        DataFolderPrivacy.excludeFromBackup(link, notes::add, exclude) shouldBe false
        DataFolderPrivacy.excludeFromBackup(dangling, notes::add, exclude) shouldBe false

        marked.shouldBeEmpty()
        File(temp, "missing-target").exists() shouldBe false
        notes shouldHaveSize 2
        notes.forEach { it shouldContain "symbolic link" }
    }

    @Test
    fun `a real disposable folder is created and marked`() {
        val dir = File(temp, "cache/images.noindex")
        val marked = mutableListOf<File>()

        DataFolderPrivacy.excludeFromBackup(dir, notes::add) { folder, _ ->
            marked += folder
            true
        } shouldBe true

        dir.isDirectory shouldBe true
        marked shouldBe listOf(dir)
        notes.shouldBeEmpty()
    }

    @Test
    fun `a failing exclusion is noted, not thrown`() {
        DataFolderPrivacy.excludeFromBackup(File(temp, "logs.noindex"), notes::add) { _, _ -> throw IOException("denied") } shouldBe false
        notes.single() shouldContain "denied"
    }

    @Test
    fun `protect leaves the target of a symlinked disposable folder in Time Machine on macOS`() {
        assumeTrue(Platform.current == Platform.MACOS)
        val target = File(temp, "users-own").apply { mkdirs() }
        Files.createSymbolicLink(File(temp, "logs.noindex").toPath(), target.toPath())
        val dirs = ZoukanDirs(temp, hidesFromSpotlight = true)

        DataFolderPrivacy.protect(dirs, notes::add)

        BackupExclusion.isExcluded(target) shouldBe false
        Files.isSymbolicLink(dirs.logs.toPath()) shouldBe true
        dirs.disposableFolders.filter { it != dirs.logs }.forEach { BackupExclusion.isExcluded(it) shouldBe true }
        BackupExclusion.isExcluded(dirs.updates) shouldBe true
        notes.single() shouldContain "symbolic link"
    }

    @Test
    fun `the xattr calls act on a link itself, never on its target, on macOS`() {
        assumeTrue(Platform.current == Platform.MACOS)
        val target = File(temp, "users-own").apply { mkdirs() }
        BackupExclusion.exclude(target, notes::add) shouldBe true
        val link = File(temp, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        // The target's exclusion is not seen or removed through the link
        BackupExclusion.isExcluded(link) shouldBe false
        BackupExclusion.hasAttribute(link, notes::add) shouldBe false
        BackupExclusion.include(link, notes::add) shouldBe true
        BackupExclusion.isExcluded(target) shouldBe true

        // Marking the link, whether or not the file system allows it, leaves the target unmarked
        BackupExclusion.include(target, notes::add) shouldBe true
        BackupExclusion.exclude(link, notes::add)
        BackupExclusion.isExcluded(target) shouldBe false
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

    @Test
    fun `protect changes nothing outside the data folder, with every kind of neighbour around it`() {
        FileSystemSandbox.create(temp).use { sandbox ->
            // A disposable folder that is a link to the user's own folder, as a user might make one
            sandbox.link(File(sandbox.dataFolder, "logs.noindex"), sandbox.userFolder)
            sandbox.record()

            DataFolderPrivacy.protect(ZoukanDirs(sandbox.dataFolder, hidesFromSpotlight = true, isDefaultRoot = true), notes::add)
            DataFolderPrivacy.protect(ZoukanDirs(sandbox.dataFolder, hidesFromSpotlight = false, isDefaultRoot = true), notes::add)

            sandbox.assertNothingOutsideAppChanged()
        }
    }

    @Test
    fun `protect on a data folder that is a link changes neither the target's permissions nor what is in it`() {
        FileSystemSandbox.create(temp).use { sandbox ->
            val link = sandbox.link(File(sandbox.home, "app-data/Linked"), sandbox.userFolder)
            assumeTrue(link != null, "symbolic links cannot be made here")
            sandbox.addAppFolder(link!!)
            sandbox.record()

            DataFolderPrivacy.protect(ZoukanDirs(link, hidesFromSpotlight = true, isDefaultRoot = true), notes::add)

            // The linked folder is the data folder, so the app's own folders may be made in it; nothing
            // that was there changes, and the folder keeps its permissions (the link is not followed)
            sandbox.changesOutsideApp().filterNot { it.startsWith("created folder Documents/Chosen/") }.shouldBeEmpty()
        }
    }

    private companion object {
        /** `xattr -px com.apple.metadata:com_apple_backup_excludeItem` after `tmutil addexclusion` */
        const val TMUTIL_VALUE =
            "62706C69737430305F1011636F6D2E6170706C652E6261636B757064080000000000000101" +
                "00000000000000010000000000000000000000000000001C"
    }
}

class CacheFoldersTest {
    @TempDir
    lateinit var temp: File

    private val roaming get() = File(temp, "Roaming/Zoukan")
    private val local get() = File(temp, "Local/Zoukan-local")
    // As on Windows: the caches keep their plain names (the `.noindex` names are macOS's)
    private fun windowsDirs() = ZoukanDirs(roaming, local, hidesFromSpotlight = false)

    private val anotherVolume: (Path, Path) -> Unit = { from, to -> throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "another volume") }

    private fun File.write(relative: String, text: String) = File(this, relative).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    /** The user's own data, which no move may touch. */
    private fun userData() {
        roaming.write("zoukan.db", "db")
        roaming.write("cookies.json", "cookies")
        roaming.write("cache/covers/custom/abc", "custom cover")
        roaming.write("cache/covers/def", "cover")
        roaming.write("backups/auto.tachibk", "backup")
    }

    private fun userDataIsIntact() {
        File(roaming, "zoukan.db").readText() shouldBe "db"
        File(roaming, "cookies.json").readText() shouldBe "cookies"
        File(roaming, "cache/covers/custom/abc").readText() shouldBe "custom cover"
        File(roaming, "cache/covers/def").readText() shouldBe "cover"
        File(roaming, "backups/auto.tachibk").readText() shouldBe "backup"
    }

    @Test
    fun `the old caches are renamed into the local folder`() {
        userData()
        roaming.write("cache/browser/Default/Cookies", "chromium cookies")
        roaming.write("browser/install.lock", "")
        roaming.write("cache/images/journal", "journal")

        val dirs = CacheFolders.migrate(windowsDirs())

        dirs.browserCache shouldBe File(local, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "chromium cookies"
        File(dirs.browser, "install.lock").exists() shouldBe true
        File(dirs.images, "journal").readText() shouldBe "journal"
        File(roaming, "cache/browser").exists() shouldBe false
        File(roaming, "browser").exists() shouldBe false
        File(roaming, "cache/images").exists() shouldBe false
        userDataIsIntact()
    }

    @Test
    fun `on another volume the folder is copied and the old one deleted only afterwards`() {
        userData()
        roaming.write("cache/browser/Default/Cookies", "chromium cookies")
        roaming.write("cache/browser/Local State", "state")

        val dirs = CacheFolders.migrate(windowsDirs(), anotherVolume)

        dirs.browserCache shouldBe File(local, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "chromium cookies"
        File(dirs.browserCache, "Local State").readText() shouldBe "state"
        File(roaming, "cache/browser").exists() shouldBe false
        File(local, "cache/browser.moving").exists() shouldBe false
        userDataIsIntact()
    }

    @Test
    fun `a folder in use stays where it is for this run and the others still move`() {
        roaming.write("cache/browser/Default/Cookies", "chromium cookies")
        roaming.write("cache/images/journal", "journal")
        val inUse: (Path, Path) -> Unit = { from, to ->
            if (from.endsWith("browser") && from.parent.endsWith("cache")) throw AccessDeniedException(from.toString())
            Files.move(from, to)
        }

        val dirs = CacheFolders.migrate(windowsDirs(), inUse)

        dirs.browserCache shouldBe File(roaming, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "chromium cookies"
        File(local, "cache/browser").exists() shouldBe false
        dirs.images shouldBe File(local, "cache/images")
        File(dirs.images, "journal").readText() shouldBe "journal"

        // The next start moves it
        val next = CacheFolders.migrate(windowsDirs())
        next.browserCache shouldBe File(local, "cache/browser")
        File(next.browserCache, "Default/Cookies").readText() shouldBe "chromium cookies"
    }

    @Test
    fun `a copy that fails keeps the old folder whole and leaves no half copy`() {
        roaming.write("cache/browser/Default/Cookies", "chromium cookies")
        // A link inside the folder is never copied, so the copy fails
        Files.createSymbolicLink(File(roaming, "cache/browser/elsewhere").toPath(), temp.toPath())

        val dirs = CacheFolders.migrate(windowsDirs(), anotherVolume)

        dirs.browserCache shouldBe File(roaming, "cache/browser")
        File(roaming, "cache/browser/Default/Cookies").readText() shouldBe "chromium cookies"
        File(local, "cache/browser").exists() shouldBe false
        File(local, "cache/browser.moving").exists() shouldBe false
        temp.exists() shouldBe true
    }

    @Test
    fun `an old folder that is a link is left alone and used where it is`() {
        val target = File(temp, "elsewhere").apply { mkdirs() }
        File(target, "keep").writeText("keep")
        File(roaming, "cache").mkdirs()
        Files.createSymbolicLink(File(roaming, "cache/browser").toPath(), target.toPath())

        val dirs = CacheFolders.migrate(windowsDirs(), anotherVolume)

        dirs.browserCache shouldBe File(roaming, "cache/browser")
        File(target, "keep").readText() shouldBe "keep"
    }

    @Test
    fun `a leftover of the old folder is deleted once the new one exists`() {
        roaming.write("cache/browser/leftover", "old")
        local.write("cache/browser/Default/Cookies", "new")

        val dirs = CacheFolders.migrate(windowsDirs())

        dirs.browserCache shouldBe File(local, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "new"
        File(roaming, "cache/browser").exists() shouldBe false
    }

    /** Sets the modification time of [this] folder and everything in it to [daysAgo] days ago. */
    private fun File.usedDaysAgo(daysAgo: Long) {
        val time = FileTime.fromMillis(System.currentTimeMillis() - daysAgo * 24 * 60 * 60 * 1000)
        walkBottomUp().forEach { Files.setLastModifiedTime(it.toPath(), time) }
    }

    @Test
    fun `a browser profile an older version used more recently replaces the new one`() {
        local.write("cache/browser/Default/Cookies", "new")
        local.write("cache/browser/Default/Only in new", "x")
        roaming.write("cache/browser/Default/Cookies", "old, used later")
        File(local, "cache/browser").usedDaysAgo(2)
        File(roaming, "cache/browser").usedDaysAgo(1)

        val dirs = CacheFolders.migrate(windowsDirs())

        dirs.browserCache shouldBe File(local, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "old, used later"
        File(dirs.browserCache, "Default/Only in new").exists() shouldBe false
        File(roaming, "cache/browser").exists() shouldBe false
        File(local, "cache/browser.replaced").exists() shouldBe false
    }

    @Test
    fun `a browser profile used more recently in the new place is kept`() {
        local.write("cache/browser/Default/Cookies", "new, used later")
        roaming.write("cache/browser/Default/Cookies", "old")
        File(roaming, "cache/browser").usedDaysAgo(2)
        File(local, "cache/browser").usedDaysAgo(1)

        val dirs = CacheFolders.migrate(windowsDirs())

        dirs.browserCache shouldBe File(local, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "new, used later"
        File(roaming, "cache/browser").exists() shouldBe false
    }

    @Test
    fun `a plain cache keeps the new folder even when the old one is more recent`() {
        local.write("cache/images/journal", "new")
        roaming.write("cache/images/journal", "old")
        File(local, "cache/images").usedDaysAgo(2)
        File(roaming, "cache/images").usedDaysAgo(1)

        val dirs = CacheFolders.migrate(windowsDirs())

        File(dirs.images, "journal").readText() shouldBe "new"
        File(roaming, "cache/images").exists() shouldBe false
    }

    @Test
    fun `a replacement that fails half way keeps both profiles and uses the more recent one`() {
        local.write("cache/browser/Default/Cookies", "new")
        roaming.write("cache/browser/Default/Cookies", "old, used later")
        File(local, "cache/browser").usedDaysAgo(2)
        File(roaming, "cache/browser").usedDaysAgo(1)
        // Setting the new folder aside works; moving the old one in fails (a file in use)
        val inUse: (Path, Path) -> Unit = { from, to ->
            if (from.startsWith(roaming.toPath())) throw AccessDeniedException(from.toString())
            Files.move(from, to)
        }

        val dirs = CacheFolders.migrate(windowsDirs(), inUse)

        dirs.browserCache shouldBe File(roaming, "cache/browser")
        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "old, used later"
        File(local, "cache/browser/Default/Cookies").readText() shouldBe "new"
        File(local, "cache/browser.replaced").exists() shouldBe false

        // The next start finishes it
        val next = CacheFolders.migrate(windowsDirs())
        next.browserCache shouldBe File(local, "cache/browser")
        File(next.browserCache, "Default/Cookies").readText() shouldBe "old, used later"
        File(roaming, "cache/browser").exists() shouldBe false
        File(local, "cache/browser.replaced").exists() shouldBe false
    }

    @Test
    fun `a replacement cut short after the new profile was set aside is finished`() {
        // Stopped after setting the new folder aside, before the old one moved in
        local.write("cache/browser.replaced/Default/Cookies", "new")
        roaming.write("cache/browser/Default/Cookies", "old, used later")

        val dirs = CacheFolders.migrate(windowsDirs())

        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "old, used later"
        File(local, "cache/browser.replaced").exists() shouldBe false
        File(roaming, "cache/browser").exists() shouldBe false
    }

    @Test
    fun `a set-aside profile with nothing to replace it is put back`() {
        local.write("cache/browser.replaced/Default/Cookies", "new")

        val dirs = CacheFolders.migrate(windowsDirs())

        File(dirs.browserCache, "Default/Cookies").readText() shouldBe "new"
        File(local, "cache/browser.replaced").exists() shouldBe false
    }

    @Test
    fun `a staging folder left by an interrupted copy is replaced`() {
        roaming.write("cache/images/a", "a")
        local.write("cache/images.moving/stale", "stale")

        val dirs = CacheFolders.migrate(windowsDirs(), anotherVolume)

        File(dirs.images, "a").readText() shouldBe "a"
        File(dirs.images, "stale").exists() shouldBe false
        File(local, "cache/images.moving").exists() shouldBe false
    }

    @Test
    fun `when the local folder cannot be made every cache stays in the data folder`() {
        roaming.write("cache/browser/Default/Cookies", "chromium cookies")
        local.parentFile.mkdirs()
        local.writeText("a file where the folder should be")

        val dirs = CacheFolders.migrate(windowsDirs())

        dirs.browserCache shouldBe File(roaming, "cache/browser")
        dirs.browser shouldBe File(roaming, "browser")
        dirs.images shouldBe File(roaming, "cache/images")
        File(roaming, "cache/browser/Default/Cookies").readText() shouldBe "chromium cookies"
    }

    @Test
    fun `a new install has nothing to move and uses the local folder`() {
        val dirs = CacheFolders.migrate(windowsDirs()).create()
        dirs.browserCache shouldBe File(local, "cache/browser")
        dirs.browserCache.isDirectory shouldBe true
        File(roaming, "cache/browser").exists() shouldBe false
    }

    @Test
    fun `a data folder without a local folder is left as it is`() {
        val dirs = ZoukanDirs(roaming)
        roaming.write("cache/browser/Default/Cookies", "chromium cookies")

        CacheFolders.migrate(dirs, anotherVolume) shouldBe dirs
        File(roaming, "cache/browser/Default/Cookies").readText() shouldBe "chromium cookies"
        local.exists() shouldBe false
    }

    @Test
    fun `moving the caches, by rename or by copy, changes nothing outside the data and local folders`() {
        for (move in listOf<((Path, Path) -> Unit)?>(null, anotherVolume)) {
            FileSystemSandbox.create(temp).use { sandbox ->
                val data = sandbox.dataFolder
                // As %LOCALAPPDATA%, which is there already; Zoukan-local in it is Zoukan's
                val localRoot = File(sandbox.home, "app-local/Zoukan-local").apply { parentFile.mkdirs() }
                sandbox.addAppFolder(localRoot)
                data.write("cache/browser/Default/Cookies", "chromium cookies")
                data.write("cache/images/journal", "journal")
                // A link in a cache to the user's own folder: the copy must not follow it
                sandbox.link(File(data, "cache/images/elsewhere"), sandbox.userFolder)
                sandbox.record()
                val dirs = ZoukanDirs(data, localRoot, hidesFromSpotlight = false)

                if (move == null) CacheFolders.migrate(dirs) else CacheFolders.migrate(dirs, move)

                sandbox.assertNothingOutsideAppChanged()
            }
        }
    }

    @Test
    fun `deleting a cache folder with links out of it changes nothing outside it`() {
        FileSystemSandbox.create(temp).use { sandbox ->
            val cache = File(sandbox.dataFolder, "cache/images").apply { mkdirs() }
            File(cache, "entry").writeText("x")
            sandbox.link(File(cache, "to-user"), sandbox.userFolder)
            sandbox.link(File(cache, "to-read-only"), sandbox.readOnlyFolder)
            sandbox.record()

            CacheFolders.deleteTree(cache.toPath())
            CacheFolders.deleteTree(File(sandbox.dataFolder, "cache").toPath())

            sandbox.assertNothingOutsideAppChanged()
        }
    }

    @Test
    fun `deleting a folder never follows a link out of it`() {
        val outside = File(temp, "outside").apply { mkdirs() }
        File(outside, "keep").writeText("keep")
        val folder = File(temp, "folder").apply { mkdirs() }
        File(folder, "file").writeText("x")
        Files.createSymbolicLink(File(folder, "link").toPath(), outside.toPath())

        CacheFolders.deleteTree(folder.toPath()) shouldBe false
        File(outside, "keep").readText() shouldBe "keep"
        File(folder, "file").exists() shouldBe false
    }

    private fun attrs(
        regular: Boolean = false,
        directory: Boolean = false,
        symbolicLink: Boolean = false,
        other: Boolean = false,
    ) = object : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(0)
        override fun lastAccessTime(): FileTime = FileTime.fromMillis(0)
        override fun creationTime(): FileTime = FileTime.fromMillis(0)
        override fun isRegularFile() = regular
        override fun isDirectory() = directory
        override fun isSymbolicLink() = symbolicLink
        override fun isOther() = other
        override fun size() = 0L
        override fun fileKey(): Any? = null
    }

    @Test
    fun `Windows reparse-tagged directory resolving to itself is not a link, while a junction is a link`() {
        val path = Paths.get("cache", "browser")
        val reparseDir = attrs(directory = true, other = true)
        val plainDir = attrs(directory = true)

        CacheFolders.isLink(path, reparseDir, resolvesToItself = { true }, windows = true) shouldBe false
        CacheFolders.isPlainFile(path, reparseDir, resolvesToItself = { true }, windows = true) shouldBe false

        CacheFolders.isLink(path, reparseDir, resolvesToItself = { false }, windows = true) shouldBe true
        CacheFolders.isPlainFile(path, reparseDir, resolvesToItself = { false }, windows = true) shouldBe false

        CacheFolders.isLink(path, plainDir, resolvesToItself = { false }, windows = true) shouldBe false
        CacheFolders.isPlainFile(path, plainDir, resolvesToItself = { false }, windows = true) shouldBe false
    }

    @Test
    fun `Windows reparse-tagged file resolving to itself is a plain file, while a symlink or elsewhere target is a link`() {
        val path = Paths.get("cache", "Cookies")
        val reparseFile = attrs(other = true)
        val regularFile = attrs(regular = true)
        val symlink = attrs(symbolicLink = true)

        CacheFolders.isLink(path, reparseFile, resolvesToItself = { true }, windows = true) shouldBe false
        CacheFolders.isPlainFile(path, reparseFile, resolvesToItself = { true }, windows = true) shouldBe true

        CacheFolders.isLink(path, reparseFile, resolvesToItself = { false }, windows = true) shouldBe true
        CacheFolders.isPlainFile(path, reparseFile, resolvesToItself = { false }, windows = true) shouldBe false

        CacheFolders.isLink(path, symlink, resolvesToItself = { true }, windows = true) shouldBe true
        CacheFolders.isPlainFile(path, symlink, resolvesToItself = { true }, windows = true) shouldBe false

        CacheFolders.isLink(path, regularFile, resolvesToItself = { false }, windows = true) shouldBe false
        CacheFolders.isPlainFile(path, regularFile, resolvesToItself = { false }, windows = true) shouldBe true
    }

    @Test
    fun `non-Windows other file is a link and IOException from resolvesToItself fails closed`() {
        val path = Paths.get("cache", "entry")
        val otherFile = attrs(other = true)
        val reparseDir = attrs(directory = true, other = true)

        CacheFolders.isLink(path, otherFile, resolvesToItself = { true }, windows = false) shouldBe true
        CacheFolders.isPlainFile(path, otherFile, resolvesToItself = { true }, windows = false) shouldBe false

        CacheFolders.isLink(path, otherFile, resolvesToItself = { throw IOException("unreadable") }, windows = true) shouldBe true
        CacheFolders.isPlainFile(path, otherFile, resolvesToItself = { throw IOException("unreadable") }, windows = true) shouldBe false

        CacheFolders.isLink(path, reparseDir, resolvesToItself = { throw IOException("unreadable") }, windows = true) shouldBe true
        CacheFolders.isPlainFile(path, reparseDir, resolvesToItself = { throw IOException("unreadable") }, windows = true) shouldBe false
    }
}
