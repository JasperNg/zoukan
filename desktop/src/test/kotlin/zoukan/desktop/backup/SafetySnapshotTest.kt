package zoukan.desktop.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.compat.localized
import zoukan.desktop.storage.ZoukanStorage
import zoukan.i18n.desktop.DMR
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
    fun `a sync snapshot of an unchanged library keeps the last one instead of rotating older ones out`() = runBlocking<Unit> {
        var library = "the library before the first sync"
        val snapshots = snapshot(write = { it.writeText(library) })
        val good = snapshots.take(SnapshotReason.SYNC).getOrThrow()
        library = "the library after a sync whose restore failed in part"
        val first = snapshots.take(SnapshotReason.SYNC).getOrThrow()

        // The same entries fail on every sync and the library does not change: more failing syncs
        // than are kept must not push the snapshot before the first sync out
        repeat(SafetySnapshot.KEEP + 2) { snapshots.take(SnapshotReason.SYNC).getOrThrow() shouldBe first }

        folder.listFiles()!!.map { it.name } shouldContainExactlyInAnyOrder listOf(good.name, first.name)
        good.readText() shouldBe "the library before the first sync"
    }

    @Test
    fun `a sync snapshot is always taken when the library changed, even by one byte`() = runBlocking<Unit> {
        var library = "library 1"
        val snapshots = snapshot(write = { it.writeText(library) })
        val first = snapshots.take(SnapshotReason.SYNC).getOrThrow()
        library = "library 2"

        val second = snapshots.take(SnapshotReason.SYNC).getOrThrow()

        (second != first) shouldBe true
        second.readText() shouldBe "library 2"
        first.readText() shouldBe "library 1"
    }

    @Test
    fun `other reasons keep a snapshot for each change, even of the same library`() = runBlocking<Unit> {
        // The user starts each of these, and expects each to have its backup
        val snapshots = snapshot(write = { it.writeText("same") })
        val files = (1..3).map { snapshots.take(SnapshotReason.RESTORE).getOrThrow() }
        files.toSet().size shouldBe 3
        // A sync's snapshot is not compared with another reason's
        val sync = snapshots.take(SnapshotReason.SYNC).getOrThrow()
        (sync in files) shouldBe false
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
        // In the app's words; the error itself is in the app log
        asked shouldBe localized(DMR.strings.safety_backup_could_not_be_written)
        snapshots.guard(SnapshotReason.CLEAR_DATABASE) { true } shouldBe true
    }

    @Test
    fun `a failed write's message never reaches the prompt or the sync notification, which can show paths`() {
        // An I/O error names the file, and so the data folder and the user's name; the text also goes
        // to the OS notification centre when sync fails
        val path = File(root, "backups/safety/safety_sync_2026-01-01_10-00-00.tachibk").path
        val texts = listOf(
            java.nio.file.AccessDeniedException(path),
            java.nio.file.FileSystemException(path, null, "No space left on device"),
            IOException("Could not write $path"),
            IllegalStateException(path),
        ).map { SafetySnapshot.errorText(it) }

        texts.forEach { text ->
            text shouldNotContain root.path
            text shouldBe localized(DMR.strings.safety_backup_could_not_be_written)
        }
    }

    @Test
    fun `a write that took too long says so in the app's words`() = runBlocking<Unit> {
        val error = snapshot(write = { awaitCancellation() }, timeout = 200).take(SnapshotReason.SYNC).exceptionOrNull()!!

        // Not kotlinx's "Timed out waiting for 200 ms"
        SafetySnapshot.errorText(error) shouldBe localized(DMR.strings.safety_backup_took_too_long)
    }

    @Test
    fun `running out of memory while writing the snapshot is a failed snapshot the user decides on`() = runBlocking<Unit> {
        // The backup of a large library is built in memory, and released when the write fails: the
        // user is asked to continue without a safety backup, as for any failed write
        val snapshots = snapshot(write = { throw OutOfMemoryError("Java heap space") })
        snapshots.take(SnapshotReason.CLEAR_DATABASE).isFailure shouldBe true
        var asked: String? = null
        snapshots.guard(SnapshotReason.RESTORE) {
            asked = it
            false
        } shouldBe false
        // Said in the app's words, not the JVM's ("Java heap space")
        asked shouldBe localized(DMR.strings.safety_backup_too_large)
    }

    @Test
    fun `other fatal errors while writing the snapshot still go on`() = runBlocking<Unit> {
        shouldThrow<NoClassDefFoundError> { snapshot(write = { throw NoClassDefFoundError("x") }).take(SnapshotReason.CLEAR_DATABASE) }
        shouldThrow<InternalError> { snapshot(write = { throw InternalError("x") }).take(SnapshotReason.CLEAR_DATABASE) }
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

    /** Two sync snapshots in [folder]: an older one and the one just written. */
    private fun twoSnapshots(older: ByteArray, written: ByteArray): Pair<File, File> {
        folder.mkdirs()
        return File(folder, "safety_sync_2026-01-01_10-00-00.tachibk").apply { writeBytes(older) } to
            File(folder, "safety_sync_2026-01-01_10-00-01.tachibk").apply { writeBytes(written) }
    }

    @Test
    fun `snapshots are compared through a small buffer, so a difference in the last byte counts`() {
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        val (older, written) = twoSnapshots(bytes, bytes.copyOf().also { it[it.size - 1] = 7 })

        SafetySnapshot.sameAsNewest(folder, SnapshotReason.SYNC, written, bufferSize = 16) shouldBe null
        written.exists() shouldBe true

        written.writeBytes(bytes)
        SafetySnapshot.sameAsNewest(folder, SnapshotReason.SYNC, written, bufferSize = 16) shouldBe older
        written.exists() shouldBe false
    }

    @Test
    fun `a read that fails part way through the compare keeps the new snapshot`() {
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        val (older, written) = twoSnapshots(bytes, bytes)
        val failing = { file: File ->
            object : java.io.FilterInputStream(file.inputStream()) {
                var read = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (read > 4_000) throw IOException("The disk went away")
                    return super.read(b, off, len).also { if (it > 0) read += it }
                }
            }
        }

        SafetySnapshot.sameAsNewest(folder, SnapshotReason.SYNC, written, bufferSize = 1024, open = failing) shouldBe null

        written.exists() shouldBe true
        older.exists() shouldBe true
    }
}
