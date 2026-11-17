package zoukan.desktop.backup

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.storage.ZoukanStorage
import java.io.File
import java.io.IOException
import java.time.LocalDateTime

class SafetySnapshotTest {
    @TempDir
    lateinit var root: File

    private val folder get() = File(root, "safety")
    private var clock = LocalDateTime.of(2026, 1, 1, 10, 0, 0)

    private fun snapshot(write: suspend (File) -> Unit = { it.writeText("backup") }, timeout: Long = 10_000) =
        SafetySnapshot(folder = { folder }, write = write, timeoutMillis = timeout, now = { clock.also { clock = clock.plusSeconds(1) } })

    @Test
    fun `a snapshot is written before the change and names its reason`() = runBlocking<Unit> {
        val file = snapshot().take(SnapshotReason.RESTORE).getOrThrow()

        file.parentFile shouldBe folder
        file.name shouldBe "safety_restore_2026-01-01_10-00-00.tachibk"
        file.readText() shouldBe "backup"
    }

    @Test
    fun `rotation keeps the newest five of each reason and touches nothing else`() = runBlocking<Unit> {
        val snapshots = snapshot()
        folder.mkdirs()
        val foreign = File(folder, "notes.txt").apply { writeText("mine") }
        val autoBackup = File(folder, "zoukan_2020-01-01_00-00.tachibk").apply { writeText("old") }
        val sync = snapshots.take(SnapshotReason.SYNC).getOrThrow()

        val restores = (1..7).map { snapshots.take(SnapshotReason.RESTORE).getOrThrow() }

        folder.listFiles()!!.map { it.name } shouldContainExactlyInAnyOrder
            (restores.takeLast(SafetySnapshot.KEEP) + listOf(sync, foreign, autoBackup)).map { it.name }
    }

    @Test
    fun `two snapshots in the same second get different names`() = runBlocking<Unit> {
        val fixed = LocalDateTime.of(2026, 1, 1, 10, 0, 0)
        val snapshots = SafetySnapshot(folder = { folder }, write = { it.writeText("b") }, now = { fixed })

        val first = snapshots.take(SnapshotReason.MIGRATION).getOrThrow()
        val second = snapshots.take(SnapshotReason.MIGRATION).getOrThrow()

        (first != second) shouldBe true
        second.name shouldBe "safety_migration_2026-01-01_10-00-00-2.tachibk"
    }

    @Test
    fun `a failed write is reported, not thrown, and the user decides`() = runBlocking<Unit> {
        val snapshots = snapshot(write = { throw IOException("disk full") })
        snapshots.take(SnapshotReason.CLEAR_DATABASE).isFailure shouldBe true

        var asked: String? = null
        snapshots.guard(SnapshotReason.CLEAR_DATABASE) {
            asked = it
            false
        } shouldBe false
        asked!! shouldContain "disk full"
        snapshots.guard(SnapshotReason.CLEAR_DATABASE) { true } shouldBe true
    }

    @Test
    fun `a saved snapshot goes ahead without asking`() = runBlocking<Unit> {
        snapshot().guard(SnapshotReason.CLEAR_CHAPTERS) { error("must not ask") } shouldBe true
    }

    @Test
    fun `a prompt that cannot be shown cancels the change`() = runBlocking<Unit> {
        snapshot(write = { throw IOException("x") }).guard(SnapshotReason.MIGRATION) { throw java.awt.HeadlessException() } shouldBe false
    }

    @Test
    fun `a write that never ends fails after the timeout instead of blocking the change`() = runBlocking<Unit> {
        val result = snapshot(write = { awaitCancellation() }, timeout = 200).take(SnapshotReason.RESTORE)
        result.isFailure shouldBe true
    }

    @Test
    fun `snapshots go to the app's data folder, not the shared automatic backup folder`() {
        val dirs = ZoukanDirs(root)
        val storage = ZoukanStorage(dirs, InMemoryPreferenceStore())
        val safety = SafetySnapshot.folder(dirs).absoluteFile.normalize()
        val autoBackups = storage.autoBackups.absoluteFile.normalize()

        safety.startsWith(dirs.root.absoluteFile.normalize()) shouldBe true
        safety.startsWith(autoBackups) shouldBe false
        // A storage folder chosen elsewhere (shared with the Android app) never holds them either
        val shared = File(root.parentFile, "shared-${System.nanoTime()}")
        storage.folderPreference.set(shared.absolutePath)
        safety.startsWith(storage.root.absoluteFile.normalize()) shouldBe false
    }
}
