package zoukan.desktop.app

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.TestHome
import java.io.File
import java.util.concurrent.CompletableFuture
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
        val instance = acquireAndForget(root)
        try {
            val dropped = java.lang.ref.WeakReference(Any())
            // A full collection clears every weak reference to what is no longer reachable at once:
            // once the canary is cleared, an instance (and its channel, whose cleaner would free the
            // lock) that nothing kept would be cleared too. Still reachable, its cleaner never runs
            Await.until("a full garbage collection") {
                System.gc()
                dropped.get() == null
            }
            instance.get().shouldNotBeNull()
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
            SingleInstance.acquireForCommand(root, "test") shouldBe null
            File(root, "zoukan.port").readText() shouldBe appPort
        } finally {
            app.instance.close()
        }
        // Alone, the command holds the folder and writes no port file of its own (a command has
        // no window to show), and the app starting beside it only refuses too
        val command = SingleInstance.acquireForCommand(root, "test").shouldNotBeNull()
        try {
            File(root, "zoukan.port").exists() shouldBe false
            SingleInstance.acquire(root) { error("the command must not be shown") }
                .shouldBeInstanceOf<SingleInstance.Result.CommandRunning>()
        } finally {
            // And closing it frees the folder again
            command.close()
        }
        SingleInstance.acquireForCommand(root, "test").shouldNotBeNull().close()
    }

    @Test
    fun `acquire while a command holds the folder reports a command, not a window`() {
        val command = SingleInstance.acquireForCommand(root, "library update").shouldNotBeNull()
        try {
            SingleInstance.acquire(root) { error("a command has no window") } shouldBe
                SingleInstance.Result.CommandRunning("library update")
        } finally {
            command.close()
        }
        // Closing the command removes its marker: the app starts normally afterwards
        val app = SingleInstance.acquire(root) {}
        app.shouldBeInstanceOf<SingleInstance.Result.Acquired>()
        app.instance.close()
    }

    @Test
    fun `a lock held by another process is held`(@TempDir scratch: File) {
        // Another process holding the lock makes tryLock return null rather than throw: start a
        // second JVM that takes it like a running Zoukan, then try from this one
        val java = File(System.getProperty("java.home"), "bin/java").path
        // Through an argument file: the test classpath can be longer than a Windows command line
        val argFile = File(scratch, "args").apply {
            writeText("-cp \"${System.getProperty("java.class.path").replace("\\", "\\\\")}\"\n")
        }
        val process = ProcessBuilder(listOf(java) + TestHome.childJvmArguments() + listOf("@${argFile.path}", SingleInstanceLockHolder::class.java.name, root.path))
            .redirectErrorStream(true)
            .start()
        try {
            val output = StringBuffer()
            val locked = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().lineSequence()
                    .onEach { output.appendLine(it) }
                    .any { it == "locked" }
            }
            assertTrue(runCatching { locked.get(60, TimeUnit.SECONDS) }.getOrDefault(false)) {
                "The child JVM did not take the lock:\n$output"
            }
            val childPort = File(root, "zoukan.port").readText()

            // The app asks the other process to show its window and does not start
            SingleInstance.acquire(root) { error("the other process holds the lock") } shouldBe
                SingleInstance.Result.AlreadyRunning(shown = true)
            // A command refuses too
            SingleInstance.acquireForCommand(root, "test") shouldBe null
            // And neither took over the other process's port file
            File(root, "zoukan.port").readText() shouldBe childPort

            // Once the other process ends, the folder is free
            process.outputStream.close()
            process.waitFor(30, TimeUnit.SECONDS) shouldBe true
            SingleInstance.acquireForCommand(root, "test").shouldNotBeNull().close()
        } finally {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            SingleInstance.closeAll()
        }
    }

    /** As runZoukanApp does: only checks that it got the lock and keeps no reference. */
    /** Acquires the lock and keeps only a weak reference to the instance, as a caller that drops it. */
    private fun acquireAndForget(root: File): java.lang.ref.WeakReference<SingleInstance> =
        java.lang.ref.WeakReference(SingleInstance.acquire(root) {}.shouldBeInstanceOf<SingleInstance.Result.Acquired>().instance)
}
