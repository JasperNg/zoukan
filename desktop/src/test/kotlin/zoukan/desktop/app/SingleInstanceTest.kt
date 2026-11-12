package zoukan.desktop.app

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SingleInstanceTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `a second instance shows the first one and does not start`() {
        val shown = CountDownLatch(1)
        val first = SingleInstance.acquire(root) { shown.countDown() }
        first.shouldBeInstanceOf<SingleInstance.Result.Acquired>()
        try {
            val second = SingleInstance.acquire(root) { error("the second instance must not listen") }
            second shouldBe SingleInstance.Result.AlreadyRunning(shown = true)
            shown.await(5, TimeUnit.SECONDS) shouldBe true
        } finally {
            first.instance.close()
        }
        // Once the first closes, the folder is free again
        val third = SingleInstance.acquire(root) {}
        third.shouldBeInstanceOf<SingleInstance.Result.Acquired>()
        third.instance.close()
    }

    @Test
    fun `a wrong token is ignored`() {
        val first = SingleInstance.acquire(root) { error("shown by a wrong token") }
        first.shouldBeInstanceOf<SingleInstance.Result.Acquired>()
        try {
            val port = File(root, "zoukan.port").readText().substringBefore(' ')
            File(root, "zoukan.port").writeText("$port wrong")
            SingleInstance.acquire(root) {} shouldBe SingleInstance.Result.AlreadyRunning(shown = false)
        } finally {
            first.instance.close()
        }
    }

    @Test
    fun `the lock holds after the caller drops the instance and the garbage collector runs`() {
        acquireAndForget(root)
        try {
            val dropped = java.lang.ref.WeakReference(Any())
            repeat(20) {
                System.gc()
                Thread.sleep(50)
            }
            dropped.get() shouldBe null
            SingleInstance.acquire(root) {}.shouldBeInstanceOf<SingleInstance.Result.AlreadyRunning>()
        } finally {
            // Windows cannot delete the temporary folder while the lock file is open
            SingleInstance.closeAll()
        }
    }

    @Test
    fun `a command refuses while the app runs and frees the folder when it is done`() {
        val app = SingleInstance.acquire(root) { error("the command must not show the app") }
        app.shouldBeInstanceOf<SingleInstance.Result.Acquired>()
        val appPort = File(root, "zoukan.port").readText()
        try {
            // The command only refuses: the app's port file stays the app's, so its "show"
            // channel is untouched
            SingleInstance.acquireForCommand(root) shouldBe null
            File(root, "zoukan.port").readText() shouldBe appPort
        } finally {
            app.instance.close()
        }
        // Alone, the command holds the folder and writes no port file of its own (a command has
        // no window to show), and the app starting beside it only refuses too
        val command = SingleInstance.acquireForCommand(root).shouldNotBeNull()
        try {
            File(root, "zoukan.port").exists() shouldBe false
            SingleInstance.acquire(root) { error("the command must not be shown") }
                .shouldBeInstanceOf<SingleInstance.Result.AlreadyRunning>()
        } finally {
            // And closing it frees the folder again
            command.close()
        }
        SingleInstance.acquireForCommand(root).shouldNotBeNull().close()
    }

    /** As runZoukanApp does: only checks that it got the lock and keeps no reference. */
    private fun acquireAndForget(root: File) {
        SingleInstance.acquire(root) {}.shouldBeInstanceOf<SingleInstance.Result.Acquired>()
    }
}
