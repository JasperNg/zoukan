package zoukan.desktop.ui.settings

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Where the restore picker opens: the app's backups folder, or the storage folder's automatic backups. */
class BackupPickerFolderTest {

    @TempDir
    lateinit var temp: File

    private val manual get() = File(temp, "data/backups")
    private val automatic get() = File(temp, "storage/autobackup")

    private fun backup(dir: File, name: String) = File(dir.apply { mkdirs() }, name).writeBytes(byteArrayOf(1))

    @Test
    fun `an Android storage folder's automatic backups are offered when the app has made none`() {
        backup(automatic, "app.komikku_2026-01-02_03-04.tachibk")
        backupPickerFolder(manual, automatic) shouldBe automatic
    }

    @Test
    fun `the app's own backups folder is kept when it holds backups`() {
        backup(manual, "zoukan_2026-01-02_03-04.tachibk")
        backup(automatic, "app.komikku_2026-01-02_03-04.tachibk")
        backupPickerFolder(manual, automatic) shouldBe manual
    }

    @Test
    fun `the app's backups folder stays the start when there are no automatic backups`() {
        backupPickerFolder(manual, automatic) shouldBe manual
        // Other files, or a folder named like a backup, are no backups
        backup(automatic, "notes.txt")
        File(automatic, "old.tachibk").mkdirs()
        backupPickerFolder(manual, automatic) shouldBe manual
        // A missing storage folder (a disconnected drive)
        backupPickerFolder(manual, null) shouldBe manual
    }

    @Test
    fun `old proto backups count as backups`() {
        backup(automatic, "tachiyomi_2020-01-02_03-04.proto.gz")
        backupPickerFolder(manual, automatic) shouldBe automatic
    }

    @Test
    fun `the start folder is looked up off the calling thread`() = runBlocking {
        // Real time: the lookup runs on the IO threads, well within the bound
        backup(automatic, "app.komikku_2026-01-02_03-04.tachibk")
        val caller = Thread.currentThread()
        var lookedUpOn: Thread? = null
        val start = backupPickerStart(manual, automatic = {
            lookedUpOn = Thread.currentThread()
            automatic
        })
        start shouldBe automatic
        (lookedUpOn !== caller) shouldBe true
    }

    @Test
    fun `a storage folder that does not answer falls back to the app's backups folder`() = runTest {
        // A sleeping external drive or network share: the check blocks until the test ends
        val asleep = CountDownLatch(1)
        try {
            val start = backupPickerStart(manual, automatic = {
                asleep.await(30, TimeUnit.SECONDS)
                automatic
            }, io = Dispatchers.IO)
            start shouldBe manual
        } finally {
            asleep.countDown()
        }
    }

    @Test
    fun `a check that fails falls back to the app's backups folder`() = runTest {
        val start = backupPickerStart(
            manual,
            automatic = { throw SecurityException("access denied") },
            io = StandardTestDispatcher(testScheduler),
        )
        start shouldBe manual
    }
}
