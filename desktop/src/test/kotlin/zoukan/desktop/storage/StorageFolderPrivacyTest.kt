package zoukan.desktop.storage

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import zoukan.desktop.BackupExclusion
import zoukan.desktop.Platform
import zoukan.desktop.SpotlightExclusion
import zoukan.desktop.SpotlightExclusionHelper
import zoukan.desktop.SpotlightNative
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.testing.FileSystemSandbox
import zoukan.desktop.testing.TestHome
import zoukan.desktop.track.MapPreferenceStore
import java.io.File
import java.nio.file.Files

class StorageFolderPrivacyTest {
    @TempDir
    lateinit var temp: File

    private val notes = mutableListOf<String>()

    /**
     * The helper-failure tests below run a substituted command, which has its own stopped flag; only
     * the real helper (the opt-in test that runs it) shares the run-wide one. Cleared after every test
     * anyway, so no test leaves the real helper stopped for the tests after it.
     */
    @AfterEach
    fun resetSpotlightRun() = SpotlightExclusion.resetForTests()

    /**
     * Records the marks the way the real ones would leave them; folders in [failing] fail, and the
     * marks cannot be read while [unreadable].
     */
    private class FakeMark : StorageFolderPrivacy.FolderMark {
        val marked = mutableSetOf<File>()
        val calls = mutableListOf<Pair<List<File>, Boolean>>()
        val queries = mutableListOf<List<File>>()
        val failing = mutableSetOf<File>()
        var unreadable = false

        override fun set(dirs: List<File>, on: Boolean, note: (String) -> Unit): Set<File> {
            calls += dirs to on
            return dirs.filterNot { it in failing }.onEach { dir -> if (on) marked += dir else marked -= dir }.toSet()
        }

        override fun marked(dirs: List<File>, note: (String) -> Unit): Set<File>? {
            queries += dirs
            return if (unreadable) null else dirs.filterTo(mutableSetOf()) { it in marked }
        }

        /** Every folder a call put the mark on or took it off. */
        val touched: Set<File> get() = calls.flatMap { it.first }.toSet()
    }

    private val timeMachine = FakeMark()
    private val spotlight = FakeMark()

    private fun storage(chosen: File, spotlight: Boolean = true, timeMachine: Boolean = true) = ZoukanStorage(
        ZoukanDirs(File(temp, "data").apply { mkdirs() }),
        MapPreferenceStore(),
    ).apply {
        folderPreference.set(chosen.path)
        hideFromSpotlight.set(spotlight)
        excludeFromTimeMachine.set(timeMachine)
    }

    private fun privacy(storage: ZoukanStorage, enabled: Boolean = true) =
        StorageFolderPrivacy(storage, notes::add, enabled, timeMachine, spotlight)

    /** The Zoukan folders of [root] as the marks see them: canonical (the temporary folder may be behind a link). */
    private fun zoukanFolders(root: File) = listOf("downloads", "local", "autobackup").map { File(root.canonicalFile, it) }

    private fun paths(files: Iterable<File>) = files.map { it.path }.toSet()

    private fun dir(parent: File, name: String) = File(parent, name).apply { mkdirs() }

    @Test
    fun `only the folders Zoukan makes are chosen, never the storage folder itself`() {
        val root = File(temp, "chosen")
        ZoukanStorage.zoukanFolders(root) shouldBe listOf(File(root, "downloads"), File(root, "local"), File(root, "autobackup"))
    }

    @Test
    fun `both settings are on by default and nothing here is in backups`() {
        val storage = ZoukanStorage(ZoukanDirs(File(temp, "data")), MapPreferenceStore())
        storage.hideFromSpotlight.get() shouldBe true
        storage.excludeFromTimeMachine.get() shouldBe true
        storage.spotlightMarked.get().shouldBeEmpty()
        storage.timeMachineMarked.get().shouldBeEmpty()
        listOf(storage.hideFromSpotlight, storage.excludeFromTimeMachine, storage.spotlightMarked, storage.timeMachineMarked)
            .forEach { Preference.isAppState(it.key()) shouldBe true }
    }

    @Test
    fun `a store without string sets still serves the storage folder`() {
        val storage = ZoukanStorage(ZoukanDirs(File(temp, "data")), InMemoryPreferenceStore())
        storage.root shouldBe File(temp, "data")
    }

    @Test
    fun `settings on mark the Zoukan folders, make the missing ones and record them`() {
        val root = dir(temp, "chosen")
        File(root, "downloads/Source/Manga").mkdirs()
        File(root, "downloads/Source/Manga/Chapter.cbz").writeText("pages")
        File(root, "notes.txt").writeText("the user's")
        val storage = storage(root)
        val privacy = privacy(storage)

        privacy.refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(root)
        spotlight.marked shouldContainExactlyInAnyOrder zoukanFolders(root)
        storage.timeMachineMarked.get() shouldBe paths(zoukanFolders(root))
        storage.spotlightMarked.get() shouldBe paths(zoukanFolders(root))
        zoukanFolders(root).forEach { it.isDirectory shouldBe true }
        privacy.timeMachineApplied.value shouldBe true
        privacy.spotlightApplied.value shouldBe true
        // The downloads and the user's own files are untouched
        File(root, "downloads/Source/Manga/Chapter.cbz").readText() shouldBe "pages"
        File(root, "notes.txt").readText() shouldBe "the user's"
        notes.shouldBeEmpty()
    }

    @Test
    fun `the helper is not started again when nothing changed, Time Machine is set again`() {
        val root = dir(temp, "chosen")
        val privacy = privacy(storage(root))
        privacy.refresh()
        privacy.refresh()

        spotlight.calls shouldBe listOf(zoukanFolders(root) to true)
        timeMachine.calls shouldBe listOf(zoukanFolders(root) to true, zoukanFolders(root) to true)
        // Only the first refresh asked which folders were already marked
        spotlight.queries shouldBe listOf(zoukanFolders(root))
        timeMachine.queries shouldBe listOf(zoukanFolders(root))
    }

    @Test
    fun `a folder the user had already excluded is not recorded and keeps its mark when a setting is turned off`() {
        val root = dir(temp, "chosen")
        val usersOwn = dir(root, "downloads").canonicalFile
        timeMachine.marked += usersOwn
        spotlight.marked += usersOwn
        val storage = storage(root)
        val privacy = privacy(storage)

        privacy.refresh()

        storage.timeMachineMarked.get() shouldBe paths(zoukanFolders(root).drop(1))
        storage.spotlightMarked.get() shouldBe paths(zoukanFolders(root).drop(1))
        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(root)
        spotlight.marked shouldContainExactlyInAnyOrder zoukanFolders(root)
        privacy.timeMachineApplied.value shouldBe true
        privacy.spotlightApplied.value shouldBe true

        storage.excludeFromTimeMachine.set(false)
        privacy.refresh()

        timeMachine.marked shouldBe setOf(usersOwn)
        storage.timeMachineMarked.get().shouldBeEmpty()
        timeMachine.touched shouldNotContain usersOwn
        privacy.timeMachineApplied.value shouldBe true

        storage.hideFromSpotlight.set(false)
        privacy.refresh()

        spotlight.marked shouldBe setOf(usersOwn)
        storage.spotlightMarked.get().shouldBeEmpty()
        spotlight.touched shouldNotContain usersOwn
        privacy.spotlightApplied.value shouldBe true
    }

    @Test
    fun `a folder the user had already excluded keeps its mark when the storage folder changes`() {
        val old = dir(temp, "old")
        val new = dir(temp, "new")
        val usersOwn = dir(old, "downloads").canonicalFile
        timeMachine.marked += usersOwn
        spotlight.marked += usersOwn
        val storage = storage(old)
        val privacy = privacy(storage)
        privacy.refresh()

        storage.folderPreference.set(new.path)
        privacy.refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(new) + usersOwn
        spotlight.marked shouldContainExactlyInAnyOrder zoukanFolders(new) + usersOwn
        storage.timeMachineMarked.get() shouldBe paths(zoukanFolders(new))
        storage.spotlightMarked.get() shouldBe paths(zoukanFolders(new))
        timeMachine.touched shouldNotContain usersOwn
        spotlight.touched shouldNotContain usersOwn
    }

    @Test
    fun `when the marks cannot be read nothing new is marked or recorded until they can`() {
        val root = dir(temp, "chosen")
        val storage = storage(root)
        val privacy = privacy(storage)
        timeMachine.unreadable = true
        spotlight.unreadable = true

        privacy.refresh()

        timeMachine.calls.shouldBeEmpty()
        spotlight.calls.shouldBeEmpty()
        storage.timeMachineMarked.get().shouldBeEmpty()
        storage.spotlightMarked.get().shouldBeEmpty()
        privacy.timeMachineApplied.value shouldBe false
        privacy.spotlightApplied.value shouldBe false
        notes.any { "Could not read" in it } shouldBe true

        timeMachine.unreadable = false
        spotlight.unreadable = false
        privacy.refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(root)
        spotlight.marked shouldContainExactlyInAnyOrder zoukanFolders(root)
        storage.timeMachineMarked.get() shouldBe paths(zoukanFolders(root))
        storage.spotlightMarked.get() shouldBe paths(zoukanFolders(root))
        privacy.timeMachineApplied.value shouldBe true
        privacy.spotlightApplied.value shouldBe true
    }

    @Test
    fun `with both settings off and nothing recorded nothing is touched or made`() {
        val root = dir(temp, "chosen")
        dir(root, "downloads")
        // The user's own exclusions, made outside Zoukan
        timeMachine.marked += zoukanFolders(root)[0]
        spotlight.marked += zoukanFolders(root)[0]

        privacy(storage(root, spotlight = false, timeMachine = false)).refresh()

        timeMachine.calls.shouldBeEmpty()
        spotlight.calls.shouldBeEmpty()
        timeMachine.marked shouldBe setOf(zoukanFolders(root)[0])
        spotlight.marked shouldBe setOf(zoukanFolders(root)[0])
        File(root, "local").exists() shouldBe false
        File(root, "autobackup").exists() shouldBe false
    }

    @Test
    fun `turning a setting off takes off only the marks Zoukan recorded`() {
        val root = dir(temp, "chosen")
        val storage = storage(root)
        val privacy = privacy(storage)
        privacy.refresh()
        // Zoukan marked them all; then the user's own exclusion on a folder Zoukan does not record
        val usersOwn = dir(temp, "elsewhere").canonicalFile
        timeMachine.marked += usersOwn
        spotlight.marked += usersOwn
        storage.timeMachineMarked.set(storage.timeMachineMarked.get() - zoukanFolders(root)[1].path)
        timeMachine.calls.clear()

        storage.excludeFromTimeMachine.set(false)
        privacy.refresh()

        timeMachine.touched shouldBe setOf(zoukanFolders(root)[0], zoukanFolders(root)[2])
        timeMachine.calls.all { !it.second } shouldBe true
        // local was not recorded, so it keeps its mark like the user's own folder
        timeMachine.marked shouldBe setOf(zoukanFolders(root)[1], usersOwn)
        storage.timeMachineMarked.get().shouldBeEmpty()
        // Spotlight stays on and recorded, with no new helper run
        spotlight.calls.size shouldBe 1
        spotlight.marked shouldContainExactlyInAnyOrder zoukanFolders(root) + usersOwn
        privacy.timeMachineApplied.value shouldBe true
    }

    @Test
    fun `a failed removal keeps its record and is tried again`() {
        val root = dir(temp, "chosen")
        val storage = storage(root)
        val privacy = privacy(storage)
        privacy.refresh()
        spotlight.failing += zoukanFolders(root)[0]

        storage.hideFromSpotlight.set(false)
        privacy.refresh()

        storage.spotlightMarked.get() shouldBe setOf(zoukanFolders(root)[0].path)
        privacy.spotlightApplied.value shouldBe false

        spotlight.failing.clear()
        privacy.refresh()

        spotlight.calls.last() shouldBe (listOf(zoukanFolders(root)[0]) to false)
        storage.spotlightMarked.get().shouldBeEmpty()
        spotlight.marked.shouldBeEmpty()
        privacy.spotlightApplied.value shouldBe true
    }

    @Test
    fun `a failed mark is reported and tried again`() {
        val root = dir(temp, "chosen")
        val storage = storage(root)
        val privacy = privacy(storage)
        spotlight.failing += zoukanFolders(root)[2]
        privacy.refresh()

        privacy.spotlightApplied.value shouldBe false
        privacy.timeMachineApplied.value shouldBe true
        storage.spotlightMarked.get() shouldBe paths(zoukanFolders(root).take(2))

        spotlight.failing.clear()
        privacy.refresh()

        spotlight.calls.last() shouldBe (listOf(zoukanFolders(root)[2]) to true)
        privacy.spotlightApplied.value shouldBe true
    }

    @Test
    fun `a storage folder left behind loses only Zoukan's marks, the new one gets them`() {
        val old = dir(temp, "old")
        val new = dir(temp, "new")
        val storage = storage(old)
        val privacy = privacy(storage)
        privacy.refresh()
        val usersOwn = dir(temp, "elsewhere").canonicalFile
        spotlight.marked += usersOwn
        timeMachine.marked += usersOwn

        storage.folderPreference.set(new.path)
        privacy.refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(new) + usersOwn
        spotlight.marked shouldContainExactlyInAnyOrder zoukanFolders(new) + usersOwn
        storage.spotlightMarked.get() shouldBe paths(zoukanFolders(new))
        storage.timeMachineMarked.get() shouldBe paths(zoukanFolders(new))
        timeMachine.touched shouldNotContain usersOwn
        spotlight.touched shouldNotContain usersOwn
        // The old folder's downloads are still there
        zoukanFolders(old).forEach { it.isDirectory shouldBe true }
    }

    @Test
    fun `an unavailable new storage folder waits, the old one is still cleared`() {
        val old = dir(temp, "old")
        val new = File(temp, "unplugged/Zoukan")
        val storage = storage(old)
        val privacy = privacy(storage)
        privacy.refresh()

        storage.folderPreference.set(new.path)
        privacy.refresh()

        // The old folder lost its marks; nothing was made where the drive should be
        timeMachine.marked.shouldBeEmpty()
        spotlight.marked.shouldBeEmpty()
        storage.spotlightMarked.get().shouldBeEmpty()
        File(temp, "unplugged").exists() shouldBe false
        privacy.spotlightApplied.value shouldBe false
        privacy.timeMachineApplied.value shouldBe false

        // The drive is connected again
        new.mkdirs()
        privacy.refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(new)
        spotlight.marked shouldContainExactlyInAnyOrder zoukanFolders(new)
        privacy.spotlightApplied.value shouldBe true
        privacy.timeMachineApplied.value shouldBe true
    }

    @Test
    fun `a recorded folder that cannot be reached keeps its record until it can`() {
        val old = dir(temp, "old")
        val storage = storage(old)
        val privacy = privacy(storage)
        privacy.refresh()
        val parked = File(temp, "parked")
        old.renameTo(parked)

        storage.hideFromSpotlight.set(false)
        storage.excludeFromTimeMachine.set(false)
        privacy.refresh()

        storage.spotlightMarked.get() shouldBe paths(zoukanFolders(old))
        storage.timeMachineMarked.get() shouldBe paths(zoukanFolders(old))
        spotlight.calls.size shouldBe 1

        parked.renameTo(old)
        privacy.refresh()

        storage.spotlightMarked.get().shouldBeEmpty()
        storage.timeMachineMarked.get().shouldBeEmpty()
        spotlight.marked.shouldBeEmpty()
        timeMachine.marked.shouldBeEmpty()
    }

    @Test
    fun `a missing storage folder is skipped and nothing is made`() {
        val missing = File(temp, "unplugged/Zoukan")
        val privacy = privacy(storage(missing))

        privacy.refresh()

        File(temp, "unplugged").exists() shouldBe false
        timeMachine.calls.shouldBeEmpty()
        spotlight.calls.shouldBeEmpty()
        privacy.spotlightApplied.value shouldBe false
        notes.size shouldBe 1
    }

    @Test
    fun `a folder whose name differs in case is never marked or replaced`() {
        val root = dir(temp, "home")
        val users = dir(root, "Downloads")
        File(users, "invoice.pdf").writeText("the user's")
        val caseInsensitive = File(root, "downloads").exists()

        privacy(storage(root)).refresh()

        val marked = timeMachine.marked + spotlight.marked
        marked.none { it.name == "Downloads" } shouldBe true
        marked.all { it.name in ZoukanStorage.ZOUKAN_FOLDER_NAMES } shouldBe true
        // The other Zoukan folders are still marked
        timeMachine.marked shouldContainExactlyInAnyOrder (if (caseInsensitive) zoukanFolders(root).drop(1) else zoukanFolders(root))
        if (caseInsensitive) {
            // On a case-insensitive volume "downloads" is the user's folder: not made, not marked
            root.list()!!.toSet() shouldBe setOf("Downloads", "local", "autobackup")
            notes.single() shouldContain "downloads"
        }
        File(users, "invoice.pdf").readText() shouldBe "the user's"
    }

    @Test
    fun `a folder reached through a link is never marked`() {
        val root = dir(temp, "chosen")
        val target = dir(temp, "users-folder")
        Files.createSymbolicLink(File(root, "local").toPath(), target.toPath())

        privacy(storage(root)).refresh()

        val touched = timeMachine.touched + spotlight.touched
        touched shouldBe setOf(zoukanFolders(root)[0], zoukanFolders(root)[2])
        touched shouldNotContain target.canonicalFile
        Files.isSymbolicLink(File(root, "local").toPath()) shouldBe true
        notes.single() shouldContain "local"
    }

    @Test
    fun `a storage folder reached through a link is followed to the real one`() {
        val real = dir(temp, "real")
        val link = File(temp, "link")
        Files.createSymbolicLink(link.toPath(), real.toPath())

        privacy(storage(link)).refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(real)
        notes.shouldBeEmpty()
    }

    /** The real Time Machine mark (an attribute on the folder, nothing system-wide) and the fake Spotlight one. */
    private fun realTimeMachine(storage: ZoukanStorage) =
        StorageFolderPrivacy(storage, notes::add, enabled = true, timeMachine = StorageFolderPrivacy.FolderMark.TimeMachine, spotlight = spotlight)

    @Test
    fun `with the home folder as the storage folder only Zoukan's folders change, never the user's Downloads`() {
        FileSystemSandbox.create(temp).use { sandbox ->
            val home = sandbox.home
            // The user's own Time Machine exclusion (macOS), which turning the setting off must keep
            BackupExclusion.exclude(sandbox.downloads, notes::add)
            // On a case-insensitive volume "downloads" is the user's Downloads, so it is not Zoukan's
            val caseInsensitive = File(home, "downloads").exists()
            ZoukanStorage.zoukanFolders(home).filterNot { caseInsensitive && it.name == "downloads" }.forEach(sandbox::addAppFolder)
            sandbox.record()
            val storage = storage(home)
            val privacy = realTimeMachine(storage)

            privacy.refresh()
            storage.excludeFromTimeMachine.set(false)
            storage.hideFromSpotlight.set(false)
            privacy.refresh()

            sandbox.assertNothingOutsideAppChanged()
            File(sandbox.downloads, "invoice.pdf").readText() shouldBe "the user's invoice"
        }
    }

    @Test
    fun `a Zoukan folder that is a link leaves its target's marks, permissions and files alone`() {
        FileSystemSandbox.create(temp).use { sandbox ->
            val local = File(sandbox.storageFolder, "local")
            local.delete() shouldBe true
            assumeTrue(sandbox.link(local, sandbox.userFolder) != null, "symbolic links cannot be made here")
            sandbox.record()
            val storage = storage(sandbox.storageFolder)
            val privacy = realTimeMachine(storage)

            privacy.refresh()
            storage.excludeFromTimeMachine.set(false)
            privacy.refresh()

            sandbox.assertNothingOutsideAppChanged()
            Files.isSymbolicLink(local.toPath()) shouldBe true
        }
    }

    @Test
    fun `nothing happens on other systems`() {
        val root = dir(temp, "chosen")
        val privacy = privacy(storage(root), enabled = false)
        privacy.refresh()

        zoukanFolders(root).forEach { it.exists() shouldBe false }
        timeMachine.calls.shouldBeEmpty()
        spotlight.calls.shouldBeEmpty()
        privacy.spotlightApplied.value shouldBe true
    }

    @Test
    fun `the Time Machine exclusion is set and removed on macOS`() {
        assumeTrue(Platform.current == Platform.MACOS)
        val dir = File(temp, "downloads").apply { mkdirs() }
        File(dir, "Chapter.cbz").writeText("pages")

        BackupExclusion.exclude(dir, notes::add) shouldBe true
        BackupExclusion.isExcluded(dir) shouldBe true
        BackupExclusion.include(dir, notes::add) shouldBe true
        BackupExclusion.isExcluded(dir) shouldBe false
        // Removing it again, when it is not there, is not a failure
        BackupExclusion.include(dir, notes::add) shouldBe true

        File(dir, "Chapter.cbz").readText() shouldBe "pages"
        notes.shouldBeEmpty()
    }

    @Test
    fun `the Time Machine attribute is found with any value on macOS`() {
        assumeTrue(Platform.current == Platform.MACOS)
        val xattr = File("/usr/bin/xattr")
        assumeTrue(xattr.canExecute())
        val dir = File(temp, "downloads").apply { mkdirs() }

        BackupExclusion.hasAttribute(dir, notes::add) shouldBe false
        // Another value than the one Zoukan writes (as another tool might leave it)
        val process = ProcessBuilder(xattr.path, "-w", BackupExclusion.ATTRIBUTE, "another value", dir.path).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor() shouldBe 0
        BackupExclusion.isExcluded(dir) shouldBe false
        BackupExclusion.hasAttribute(dir, notes::add) shouldBe true
        BackupExclusion.include(dir, notes::add) shouldBe true
        BackupExclusion.hasAttribute(dir, notes::add) shouldBe false
        notes.shouldBeEmpty()

        // A folder that cannot be read is neither marked nor unmarked
        BackupExclusion.hasAttribute(File(temp, "missing"), notes::add) shouldBe null
        notes.single() shouldContain "missing"
    }

    @Test
    fun `the exclusions are no-ops elsewhere`() {
        val dir = File(temp, "downloads").apply { mkdirs() }
        BackupExclusion.exclude(dir, notes::add, enabled = false) shouldBe false
        BackupExclusion.include(dir, notes::add, enabled = false) shouldBe false
        // No helper is started: its command would fail the test
        val spotlight = SpotlightExclusion(command = { error("no helper elsewhere") }, enabled = false)
        spotlight.set(listOf(dir), true, notes::add).shouldBeEmpty()
        spotlight.excluded(listOf(dir), notes::add) shouldBe emptySet()
        notes.shouldBeEmpty()
    }

    @Test
    fun `the native Spotlight calls add and remove a folder on macOS`() {
        assumeRealSpotlight()
        val dir = File(temp, "downloads").apply { mkdirs() }
        File(dir, "Chapter.cbz").writeText("pages")
        try {
            SpotlightNative.set(dir, true, notes::add) shouldBe true
            SpotlightNative.isExcluded(dir) shouldBe true
            // Asked again: already in the list
            SpotlightNative.set(dir, true, notes::add) shouldBe true
            SpotlightNative.set(dir, false, notes::add) shouldBe true
            SpotlightNative.isExcluded(dir) shouldBe false
            // A folder that is not there is not added
            SpotlightNative.set(File(temp, "missing"), true, notes::add) shouldBe false
        } finally {
            // Never leave a test folder in the user's Search Privacy list
            SpotlightNative.set(dir, false) {}
        }
        File(dir, "Chapter.cbz").readText() shouldBe "pages"
        notes.shouldBeEmpty()
    }

    @Test
    fun `the helper process adds and removes folders in one run on macOS`() {
        assumeRealSpotlight()
        // In the test JVM's own home folder (under the build folder), never the real one
        FileSystemSandbox.create(realSpotlightParent()).use { sandbox ->
            val dirs = sandbox.storageZoukanFolders
            // The real command: this test's own Java runtime and the helper's jars (or class folders)
            val spotlight = SpotlightExclusion()
            try {
                spotlight.set(dirs, true, notes::add) shouldBe dirs.toSet()
                spotlight.excluded(dirs, notes::add) shouldBe dirs.toSet()
                spotlight.set(dirs, false, notes::add) shouldBe dirs.toSet()
                spotlight.excluded(dirs, notes::add) shouldBe emptySet()
                notes.shouldBeEmpty()
                sandbox.assertNothingOutsideAppChanged()
            } finally {
                dirs.forEach { SpotlightNative.set(it, false) {} }
            }
        }
    }

    /** Where the real-Spotlight test makes its sandboxes: in the test JVM's home folder ([TestHome]). */
    private fun realSpotlightParent() = File(TestHome.root(), HOME_TEST_PREFIX.trimEnd('-'))

    /**
     * The tests that change the real Spotlight Search Privacy list run only when asked:
     * `ZOUKAN_TEST_REAL_SPOTLIGHT=true`, or `-Dzoukan.test.realSpotlight=true` on the test JVM.
     * Sandboxes an earlier, interrupted run left behind are taken off the list and deleted first.
     */
    private fun assumeRealSpotlight() {
        assumeTrue(Platform.current == Platform.MACOS)
        assumeTrue(System.getProperty("zoukan.test.realSpotlight") == "true" || System.getenv("ZOUKAN_TEST_REAL_SPOTLIGHT") == "true")
        realSpotlightParent().listFiles { f -> f.isDirectory && !Files.isSymbolicLink(f.toPath()) }?.forEach { left ->
            left.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
                .filter { it.isDirectory && it.name in ZoukanStorage.ZOUKAN_FOLDER_NAMES }
                .forEach { SpotlightNative.set(it, false) {} }
            FileSystemSandbox.deleteTree(left)
        }
    }

    /** A helper command that runs [script] in /bin/sh. Exit 128 + n stands for signal n (a crash). */
    private fun sh(script: String): (List<String>) -> List<String> = { listOf("/bin/sh", "-c", script) }

    @Test
    fun `a crashing helper is noted, never thrown`() {
        assumeTrue(File("/bin/sh").canExecute())
        val dir = File(temp, "downloads").apply { mkdirs() }
        // SpotlightExclusion reads only the exit status and the error output; a real SIGSEGV would
        // make macOS write a crash report into the user's DiagnosticReports on every run
        SpotlightExclusion(sh("echo 'native crash' >&2; exit 139"), enabled = true).set(listOf(dir), true, notes::add).shouldBeEmpty()
        notes.single() shouldContain "exit code 139"
        notes.single() shouldContain "native crash"
    }

    @Test
    fun `a helper that fails, prints nonsense or reports a failure is noted`() {
        assumeTrue(File("/bin/sh").canExecute())
        val dirs = listOf(File(temp, "downloads"), File(temp, "local")).onEach { it.mkdirs() }

        SpotlightExclusion(sh("exit 3"), enabled = true).set(dirs, true, notes::add).shouldBeEmpty()
        notes.last() shouldContain "exit code 3"

        SpotlightExclusion(sh("echo hello; echo '7 ok'"), enabled = true).set(dirs, true, notes::add).shouldBeEmpty()
        notes.last() shouldContain "did not change"

        SpotlightExclusion(sh("echo '0 ok'; echo '1 failed'"), enabled = true).set(dirs, true, notes::add) shouldBe setOf(dirs[0])
        notes.last() shouldContain dirs[1].path
    }

    @Test
    fun `a check the helper could not answer for every folder is unknown, not none`() {
        assumeTrue(File("/bin/sh").canExecute())
        val dirs = listOf(File(temp, "downloads"), File(temp, "local")).onEach { it.mkdirs() }

        SpotlightExclusion(sh("echo '0 ok'; echo '1 failed'"), enabled = true).excluded(dirs, notes::add) shouldBe setOf(dirs[0])
        SpotlightExclusion(sh("echo '0 failed'; echo '1 failed'"), enabled = true).excluded(dirs, notes::add) shouldBe emptySet()
        notes.shouldBeEmpty()

        SpotlightExclusion(sh("echo '0 ok'; echo '1 unknown'"), enabled = true).excluded(dirs, notes::add) shouldBe null
        notes.last() shouldContain dirs[1].path
        SpotlightExclusion(sh("echo '0 ok'"), enabled = true).excluded(dirs, notes::add) shouldBe null
        SpotlightExclusion(sh("exit 3"), enabled = true).excluded(dirs, notes::add) shouldBe null
    }

    @Test
    fun `a helper that hangs is stopped at the timeout`() {
        assumeTrue(File("/bin/sh").canExecute())
        val dir = File(temp, "downloads").apply { mkdirs() }
        val started = System.nanoTime()
        SpotlightExclusion(sh("sleep 30"), timeoutMillis = 500, enabled = true).set(listOf(dir), false, notes::add).shouldBeEmpty()
        (System.nanoTime() - started < 10_000_000_000L) shouldBe true
        notes.single() shouldContain "did not finish"
    }

    @Test
    fun `no Java launcher is noted`() {
        val dir = File(temp, "downloads").apply { mkdirs() }
        SpotlightExclusion(command = { null }, enabled = true).set(listOf(dir), true, notes::add).shouldBeEmpty()
        notes.single() shouldContain "no Java launcher"
    }

    @Test
    fun `the helper's output is read strictly`() {
        SpotlightExclusion.parse("0 ok\n1 failed\n2 ok\nwarning: 3 ok here\n9 ok\n", 3) shouldBe setOf(0, 2)
        SpotlightExclusion.parse("", 3).shouldBeEmpty()
    }

    @Test
    fun `the helper refuses bad arguments`() {
        assumeTrue(Platform.current == Platform.MACOS)
        val argv = SpotlightExclusion.helperCommand(listOf("delete", "/"))!!
        val process = ProcessBuilder(TestHome.javaCommand(argv)).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor() shouldBe SpotlightExclusionHelper.USAGE
    }

    private companion object {
        /** The folder, in the test JVM's home folder, of [assumeRealSpotlight]'s sandboxes. */
        const val HOME_TEST_PREFIX = "zoukan-spotlight-test-"
    }
}
