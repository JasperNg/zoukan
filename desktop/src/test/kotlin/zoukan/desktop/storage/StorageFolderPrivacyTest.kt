package zoukan.desktop.storage

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.io.File
import java.util.UUID

class StorageFolderPrivacyTest {
    @TempDir
    lateinit var temp: File

    private val notes = mutableListOf<String>()

    /** Records the marks the way the real ones would leave them. */
    private class FakeMark : StorageFolderPrivacy.FolderMark {
        val marked = mutableSetOf<File>()
        val calls = mutableListOf<Pair<File, Boolean>>()

        override fun set(dirs: List<File>, on: Boolean, note: (String) -> Unit) {
            dirs.forEach { dir ->
                calls += dir to on
                if (on) marked += dir else marked -= dir
            }
        }
    }

    private val timeMachine = FakeMark()
    private val spotlight = FakeMark()

    private fun storage(chosen: String = "", spotlight: Boolean = true, timeMachine: Boolean = true) = ZoukanStorage(
        ZoukanDirs(File(temp, "data").apply { mkdirs() }),
        InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(Preference.appStateKey("storage_dir"), chosen, ""),
                InMemoryPreferenceStore.InMemoryPreference(Preference.appStateKey("zoukan_storage_hide_from_spotlight"), spotlight, true),
                InMemoryPreferenceStore.InMemoryPreference(Preference.appStateKey("zoukan_storage_exclude_from_time_machine"), timeMachine, true),
            ),
        ),
    )

    private fun privacy(storage: ZoukanStorage, enabled: Boolean = true) =
        StorageFolderPrivacy(storage, notes::add, enabled, timeMachine, spotlight)

    private fun zoukanFolders(root: File) = listOf(File(root, "downloads"), File(root, "local"), File(root, "autobackup"))

    @Test
    fun `only the folders Zoukan makes are chosen, never the storage folder itself`() {
        val root = File(temp, "chosen")
        ZoukanStorage.zoukanFolders(root) shouldBe zoukanFolders(root)
    }

    @Test
    fun `both settings are on by default`() {
        val storage = ZoukanStorage(ZoukanDirs(File(temp, "data")), InMemoryPreferenceStore())
        storage.hideFromSpotlight.get() shouldBe true
        storage.excludeFromTimeMachine.get() shouldBe true
        Preference.isAppState(storage.hideFromSpotlight.key()) shouldBe true
        Preference.isAppState(storage.excludeFromTimeMachine.key()) shouldBe true
    }

    @Test
    fun `settings on mark the Zoukan folders and create the missing ones`() {
        val root = File(temp, "chosen").apply { mkdirs() }
        File(root, "downloads/Source/Manga").mkdirs()
        File(root, "downloads/Source/Manga/Chapter.cbz").writeText("pages")
        File(root, "notes.txt").writeText("the user's")

        privacy(storage(root.path)).refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(root)
        spotlight.marked shouldContainExactlyInAnyOrder zoukanFolders(root)
        zoukanFolders(root).forEach { it.isDirectory shouldBe true }
        // The downloads and the user's own files are untouched
        File(root, "downloads/Source/Manga/Chapter.cbz").readText() shouldBe "pages"
        File(root, "notes.txt").readText() shouldBe "the user's"
        notes.shouldBeEmpty()
    }

    @Test
    fun `each setting is applied on its own, and off takes the mark away`() {
        val root = File(temp, "chosen").apply { mkdirs() }
        zoukanFolders(root).forEach { it.mkdirs() }
        timeMachine.marked += zoukanFolders(root)

        privacy(storage(root.path, spotlight = true, timeMachine = false)).refresh()

        timeMachine.marked.shouldBeEmpty()
        timeMachine.calls.all { !it.second } shouldBe true
        spotlight.marked shouldContainExactlyInAnyOrder zoukanFolders(root)
    }

    @Test
    fun `with both settings off a missing folder is not created`() {
        val root = File(temp, "chosen").apply { mkdirs() }
        File(root, "downloads").mkdirs()

        privacy(storage(root.path, spotlight = false, timeMachine = false)).refresh()

        File(root, "local").exists() shouldBe false
        File(root, "autobackup").exists() shouldBe false
        timeMachine.calls shouldBe listOf(File(root, "downloads") to false)
        spotlight.calls shouldBe listOf(File(root, "downloads") to false)
    }

    @Test
    fun `a missing storage folder is skipped and nothing is created`() {
        val missing = File(temp, "unplugged/Zoukan")

        privacy(storage(missing.path)).refresh()

        missing.exists() shouldBe false
        File(temp, "unplugged").exists() shouldBe false
        timeMachine.calls.shouldBeEmpty()
        spotlight.calls.shouldBeEmpty()
        notes.size shouldBe 1
    }

    @Test
    fun `a storage folder left behind loses its marks, the new one gets them`() {
        val old = File(temp, "old").apply { mkdirs() }
        val new = File(temp, "new").apply { mkdirs() }
        val storage = storage(old.path)
        val privacy = privacy(storage)
        privacy.refresh()

        storage.folderPreference.set(new.path)
        privacy.refresh()

        timeMachine.marked shouldContainExactlyInAnyOrder zoukanFolders(new)
        spotlight.marked shouldContainExactlyInAnyOrder zoukanFolders(new)
        // The old folder's downloads are still there
        zoukanFolders(old).forEach { it.isDirectory shouldBe true }
    }

    @Test
    fun `an old storage folder that cannot be reached is left alone`() {
        val old = File(temp, "old")
        val new = File(temp, "new").apply { mkdirs() }
        val privacy = privacy(storage(new.path))
        privacy.clear(old)

        old.exists() shouldBe false
        timeMachine.calls.shouldBeEmpty()
        spotlight.calls.shouldBeEmpty()
    }

    @Test
    fun `nothing happens on other systems`() {
        val root = File(temp, "chosen").apply { mkdirs() }
        val privacy = privacy(storage(root.path), enabled = false)
        privacy.refresh()
        privacy.clear(root)

        zoukanFolders(root).forEach { it.exists() shouldBe false }
        timeMachine.calls.shouldBeEmpty()
        spotlight.calls.shouldBeEmpty()
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
    fun `the exclusions are no-ops elsewhere`() {
        val dir = File(temp, "downloads").apply { mkdirs() }
        BackupExclusion.exclude(dir, notes::add, enabled = false) shouldBe false
        BackupExclusion.include(dir, notes::add, enabled = false) shouldBe false
        // No helper is started: its command would fail the test
        val spotlight = SpotlightExclusion(command = { error("no helper elsewhere") }, enabled = false)
        spotlight.set(listOf(dir), true, notes::add).shouldBeEmpty()
        spotlight.excluded(listOf(dir), notes::add).shouldBeEmpty()
        notes.shouldBeEmpty()
    }

    @Test
    fun `the native Spotlight calls add and remove a folder on macOS`() {
        assumeTrue(Platform.current == Platform.MACOS)
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
        assumeTrue(Platform.current == Platform.MACOS)
        val home = File(System.getProperty("user.home"))
        val root = File(home, "zoukan-spotlight-test-${UUID.randomUUID()}")
        val dirs = zoukanFolders(root).onEach { it.mkdirs() }
        // The real command: this test's own Java runtime and the helper's jars (or class folders)
        val spotlight = SpotlightExclusion()
        try {
            spotlight.set(dirs, true, notes::add) shouldBe dirs.toSet()
            spotlight.excluded(dirs, notes::add) shouldBe dirs.toSet()
            spotlight.set(dirs, false, notes::add) shouldBe dirs.toSet()
            spotlight.excluded(dirs, notes::add).shouldBeEmpty()
            notes.shouldBeEmpty()
        } finally {
            dirs.forEach { SpotlightNative.set(it, false) {} }
            root.deleteRecursively()
        }
    }

    private fun sh(script: String): (List<String>) -> List<String> = { listOf("/bin/sh", "-c", script) }

    @Test
    fun `a crashing helper is noted, never thrown`() {
        assumeTrue(File("/bin/sh").canExecute())
        val dir = File(temp, "downloads").apply { mkdirs() }
        SpotlightExclusion(sh("echo 'native crash' >&2; kill -SEGV $$"), enabled = true).set(listOf(dir), true, notes::add).shouldBeEmpty()
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
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor() shouldBe SpotlightExclusionHelper.USAGE
    }
}
