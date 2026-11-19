package zoukan.desktop.storage

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import zoukan.desktop.BackupExclusion
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.testing.FileSystemSandbox
import zoukan.desktop.track.MapPreferenceStore
import java.io.File
import java.nio.file.Files

class StorageFolderPrivacyTest {
    @TempDir
    lateinit var temp: File

    private val notes = mutableListOf<String>()

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

    private fun storage(chosen: File, timeMachine: Boolean = true) = ZoukanStorage(
        ZoukanDirs(File(temp, "data").apply { mkdirs() }),
        MapPreferenceStore(),
    ).apply {
        folderPreference.set(chosen.path)
        excludeFromTimeMachine.set(timeMachine)
    }

    private fun privacy(storage: ZoukanStorage, enabled: Boolean = true) =
        StorageFolderPrivacy(storage, notes::add, enabled, timeMachine)

    /** The Zoukan folders of [root] as the marks see them: real path (the temporary folder may be behind a link). */
    private fun zoukanFolders(root: File): List<File> {
        val base = runCatching { root.toPath().toRealPath().toFile() }.getOrElse { root.canonicalFile }
        return listOf("downloads", "local", "autobackup").map { File(base, it) }
    }

    private fun paths(files: Iterable<File>) = files.map { it.path }.toSet()

    private fun dir(parent: File, name: String) = File(parent, name).apply { mkdirs() }

    @Test
    fun `only the folders Zoukan makes are chosen, never the storage folder itself`() {
        val root = File(temp, "chosen")
        ZoukanStorage.zoukanFolders(root) shouldBe listOf(File(root, "downloads"), File(root, "local"), File(root, "autobackup"))
    }

    @Test
    fun `setting is on by default and nothing here is in backups`() {
        val storage = ZoukanStorage(ZoukanDirs(File(temp, "data")), MapPreferenceStore())
        storage.excludeFromTimeMachine.get() shouldBe true
        storage.timeMachineMarked.get().shouldBeEmpty()
        listOf(storage.excludeFromTimeMachine, storage.timeMachineMarked)
            .forEach { Preference.isAppState(it.key()) shouldBe true }
    }

    @Test
    fun `a store without string sets still serves the storage folder`() {
        val storage = ZoukanStorage(ZoukanDirs(File(temp, "data")), InMemoryPreferenceStore())
        storage.root shouldBe File(temp, "data")
    }

    @Test
    fun `setting on marks the Zoukan folders, makes the missing ones and records them`() {
        val root = dir(temp, "chosen")
        File(root, "downloads/Source/Manga").mkdirs()
        File(root, "downloads/Source/Manga/Chapter.cbz").writeText("pages")
        File(root, "notes.txt").writeText("the user's")
        val storage = storage(root)
        val privacy = privacy(storage)

        privacy.refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(root)
        storage.timeMachineMarked.get() shouldBe paths(zoukanFolders(root))
        zoukanFolders(root).forEach { it.isDirectory shouldBe true }
        privacy.timeMachineApplied.value shouldBe true
        // The downloads and the user's own files are untouched
        File(root, "downloads/Source/Manga/Chapter.cbz").readText() shouldBe "pages"
        File(root, "notes.txt").readText() shouldBe "the user's"
        notes.shouldBeEmpty()
    }

    @Test
    fun `refresh sets Time Machine again without re-querying recorded folders`() {
        val root = dir(temp, "chosen")
        val privacy = privacy(storage(root))
        privacy.refresh()
        privacy.refresh()

        timeMachine.calls shouldBe listOf(zoukanFolders(root) to true, zoukanFolders(root) to true)
        // Only the first refresh asked which folders were already marked
        timeMachine.queries shouldBe listOf(zoukanFolders(root))
    }

    @Test
    fun `a folder the user had already excluded is not recorded and keeps its mark when the setting is turned off`() {
        val root = dir(temp, "chosen")
        val usersOwn = dir(root, "downloads").canonicalFile
        timeMachine.marked += usersOwn
        val storage = storage(root)
        val privacy = privacy(storage)

        privacy.refresh()

        storage.timeMachineMarked.get() shouldBe paths(zoukanFolders(root).drop(1))
        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(root)
        privacy.timeMachineApplied.value shouldBe true

        storage.excludeFromTimeMachine.set(false)
        privacy.refresh()

        timeMachine.marked shouldBe setOf(usersOwn)
        storage.timeMachineMarked.get().shouldBeEmpty()
        timeMachine.touched shouldNotContain usersOwn
        privacy.timeMachineApplied.value shouldBe true
    }

    @Test
    fun `a folder the user had already excluded keeps its mark when the storage folder changes`() {
        val old = dir(temp, "old")
        val new = dir(temp, "new")
        val usersOwn = dir(old, "downloads").canonicalFile
        timeMachine.marked += usersOwn
        val storage = storage(old)
        val privacy = privacy(storage)
        privacy.refresh()

        storage.folderPreference.set(new.path)
        privacy.refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(new) + usersOwn
        storage.timeMachineMarked.get() shouldBe paths(zoukanFolders(new))
        timeMachine.touched shouldNotContain usersOwn
    }

    @Test
    fun `when the marks cannot be read nothing new is marked or recorded until they can`() {
        val root = dir(temp, "chosen")
        val storage = storage(root)
        val privacy = privacy(storage)
        timeMachine.unreadable = true

        privacy.refresh()

        timeMachine.calls.shouldBeEmpty()
        storage.timeMachineMarked.get().shouldBeEmpty()
        privacy.timeMachineApplied.value shouldBe false
        notes.any { "Could not read" in it } shouldBe true

        timeMachine.unreadable = false
        privacy.refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(root)
        storage.timeMachineMarked.get() shouldBe paths(zoukanFolders(root))
        privacy.timeMachineApplied.value shouldBe true
    }

    @Test
    fun `with setting off and nothing recorded nothing is touched or made`() {
        val root = dir(temp, "chosen")
        dir(root, "downloads")
        // The user's own exclusion, made outside Zoukan
        timeMachine.marked += zoukanFolders(root)[0]

        privacy(storage(root, timeMachine = false)).refresh()

        timeMachine.calls.shouldBeEmpty()
        timeMachine.marked shouldBe setOf(zoukanFolders(root)[0])
        File(root, "local").exists() shouldBe false
        File(root, "autobackup").exists() shouldBe false
    }

    @Test
    fun `turning the setting off takes off only the marks Zoukan recorded`() {
        val root = dir(temp, "chosen")
        val storage = storage(root)
        val privacy = privacy(storage)
        privacy.refresh()
        // Zoukan marked them all; then the user's own exclusion on a folder Zoukan does not record
        val usersOwn = dir(temp, "elsewhere").canonicalFile
        timeMachine.marked += usersOwn
        storage.timeMachineMarked.set(storage.timeMachineMarked.get() - zoukanFolders(root)[1].path)
        timeMachine.calls.clear()

        storage.excludeFromTimeMachine.set(false)
        privacy.refresh()

        timeMachine.touched shouldBe setOf(zoukanFolders(root)[0], zoukanFolders(root)[2])
        timeMachine.calls.all { !it.second } shouldBe true
        // local was not recorded, so it keeps its mark like the user's own folder
        timeMachine.marked shouldBe setOf(zoukanFolders(root)[1], usersOwn)
        storage.timeMachineMarked.get().shouldBeEmpty()
        privacy.timeMachineApplied.value shouldBe true
    }

    @Test
    fun `a failed removal keeps its record and is tried again`() {
        val root = dir(temp, "chosen")
        val storage = storage(root)
        val privacy = privacy(storage)
        privacy.refresh()
        timeMachine.failing += zoukanFolders(root)[0]

        storage.excludeFromTimeMachine.set(false)
        privacy.refresh()

        storage.timeMachineMarked.get() shouldBe setOf(zoukanFolders(root)[0].path)
        privacy.timeMachineApplied.value shouldBe false

        timeMachine.failing.clear()
        privacy.refresh()

        timeMachine.calls.last() shouldBe (listOf(zoukanFolders(root)[0]) to false)
        storage.timeMachineMarked.get().shouldBeEmpty()
        timeMachine.marked.shouldBeEmpty()
        privacy.timeMachineApplied.value shouldBe true
    }

    @Test
    fun `a failed mark is reported and tried again`() {
        val root = dir(temp, "chosen")
        val storage = storage(root)
        val privacy = privacy(storage)
        timeMachine.failing += zoukanFolders(root)[2]
        privacy.refresh()

        privacy.timeMachineApplied.value shouldBe false
        storage.timeMachineMarked.get() shouldBe paths(zoukanFolders(root).take(2))

        timeMachine.failing.clear()
        privacy.refresh()

        timeMachine.calls.last() shouldBe (zoukanFolders(root) to true)
        privacy.timeMachineApplied.value shouldBe true
    }

    @Test
    fun `a storage folder left behind loses only Zoukan's marks, the new one gets them`() {
        val old = dir(temp, "old")
        val new = dir(temp, "new")
        val storage = storage(old)
        val privacy = privacy(storage)
        privacy.refresh()
        val usersOwn = dir(temp, "elsewhere").canonicalFile
        timeMachine.marked += usersOwn

        storage.folderPreference.set(new.path)
        privacy.refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(new) + usersOwn
        storage.timeMachineMarked.get() shouldBe paths(zoukanFolders(new))
        timeMachine.touched shouldNotContain usersOwn
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
        storage.timeMachineMarked.get().shouldBeEmpty()
        File(temp, "unplugged").exists() shouldBe false
        privacy.timeMachineApplied.value shouldBe false

        // The drive is connected again
        new.mkdirs()
        privacy.refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(new)
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

        storage.excludeFromTimeMachine.set(false)
        privacy.refresh()

        storage.timeMachineMarked.get() shouldBe paths(zoukanFolders(old))
        timeMachine.calls.size shouldBe 1

        parked.renameTo(old)
        privacy.refresh()

        storage.timeMachineMarked.get().shouldBeEmpty()
        timeMachine.marked.shouldBeEmpty()
    }

    @Test
    fun `a missing storage folder is skipped and nothing is made`() {
        val missing = File(temp, "unplugged/Zoukan")
        val privacy = privacy(storage(missing))

        privacy.refresh()

        File(temp, "unplugged").exists() shouldBe false
        timeMachine.calls.shouldBeEmpty()
        privacy.timeMachineApplied.value shouldBe false
        notes.size shouldBe 1
    }

    @Test
    fun `an unwritable storage folder skips missing folders without throwing`() {
        assumeTrue("posix" in temp.toPath().fileSystem.supportedFileAttributeViews())
        val root = dir(temp, "readonly")
        assumeTrue(root.setWritable(false))
        try {
            val privacy = privacy(storage(root))
            privacy.refresh()

            timeMachine.calls.shouldBeEmpty()
            notes.size shouldBe 3
        } finally {
            root.setWritable(true)
        }
    }

    @Test
    fun `a folder whose name differs in case is never marked or replaced`() {
        val root = dir(temp, "home")
        val users = dir(root, "Downloads")
        File(users, "invoice.pdf").writeText("the user's")
        val caseInsensitive = File(root, "downloads").exists()

        privacy(storage(root)).refresh()

        timeMachine.marked.none { it.name == "Downloads" } shouldBe true
        timeMachine.marked.all { it.name in ZoukanStorage.ZOUKAN_FOLDER_NAMES } shouldBe true
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
    fun `a folder reached through a link or dangling link is never created or marked`() {
        val root = dir(temp, "chosen")
        val target = dir(temp, "users-folder")
        val link = File(root, "local")
        val linkCreated = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        assumeTrue(linkCreated, "symbolic links cannot be made here")
        val danglingTarget = File(temp, "missing-target")
        Files.createSymbolicLink(File(root, "autobackup").toPath(), danglingTarget.toPath())

        privacy(storage(root)).refresh()

        timeMachine.touched shouldBe setOf(zoukanFolders(root)[0])
        timeMachine.touched shouldNotContain target.canonicalFile
        Files.isSymbolicLink(link.toPath()) shouldBe true
        Files.isSymbolicLink(File(root, "autobackup").toPath()) shouldBe true
        danglingTarget.exists() shouldBe false
        notes.size shouldBe 2
        notes.any { "local" in it } shouldBe true
        notes.any { "autobackup" in it } shouldBe true
    }

    @Test
    fun `a regular file in a Zoukan folder's place is skipped with a note`() {
        val root = dir(temp, "chosen")
        File(root, "downloads").writeText("keep")

        privacy(storage(root)).refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(root).drop(1)
        File(root, "downloads").readText() shouldBe "keep"
        notes.single() shouldContain "downloads"
    }

    @Test
    fun `a storage folder reached through a link is followed to the real one`() {
        val real = dir(temp, "real")
        val link = File(temp, "link")
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), real.toPath()) }.isSuccess, "symbolic links cannot be made here")

        privacy(storage(link)).refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(real)
        notes.shouldBeEmpty()
    }

    private fun realTimeMachine(storage: ZoukanStorage) =
        StorageFolderPrivacy(storage, notes::add, enabled = true, timeMachine = StorageFolderPrivacy.FolderMark.TimeMachine)

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
        privacy.timeMachineApplied.value shouldBe true
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
        notes.shouldBeEmpty()
    }
}
