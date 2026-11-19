package zoukan.desktop.compat

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.shadows.ShadowPausedMessageQueue
import androidx.core.util.AtomicFile
import eu.kanade.tachiyomi.util.awaitSingle
import exh.util.createPartialWakeLock
import exh.util.holdSystemAwake
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.domain.source.interactor.UpdateMangaFromRemote
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import rx.Observable
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.domain.RefreshManga
import zoukan.desktop.testing.Await
import zoukan.desktop.ui.reader.KeepAwake
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException

class AtomicFileTest {
    @TempDir
    lateinit var dir: Path

    private val file: File get() = dir.resolve("table").toFile()

    private fun AtomicFile.write(text: String) {
        val stream = startWrite()
        stream.write(text.toByteArray())
        finishWrite(stream)
    }

    private fun AtomicFile.read(): String = openRead().use { String(it.readBytes()) }

    @Test
    fun `a write replaces the old content`() {
        val atomic = AtomicFile(file)
        atomic.write("old")
        atomic.write("new")
        atomic.read() shouldBe "new"
    }

    @Test
    fun `a failed write keeps the old content`() {
        val atomic = AtomicFile(file)
        atomic.write("old")
        val stream = atomic.startWrite()
        stream.write("half".toByteArray())
        atomic.failWrite(stream)
        atomic.read() shouldBe "old"
        File(file.path + ".new").exists() shouldBe false
    }

    @Test
    fun `a stale new file does not change what is read`() {
        AtomicFile(file).write("old")
        File(file.path + ".new").writeText("junk")
        AtomicFile(file).read() shouldBe "old"
    }

    @Test
    fun `a rename refused once is retried and the new content lands`() {
        var calls = 0
        val atomic = AtomicFile(file) { from, to ->
            if (calls++ == 0) throw AccessDeniedException(to.toString())
            Files.move(from, to, REPLACE_EXISTING, ATOMIC_MOVE)
        }
        AtomicFile(file).write("old")
        atomic.write("new")
        atomic.read() shouldBe "new"
    }

    @Test
    fun `a rename refused once because the file is in use is retried`() {
        // On Windows a sharing violation is a plain FileSystemException, not AccessDeniedException
        var calls = 0
        val atomic = AtomicFile(file) { from, to ->
            if (calls++ == 0) {
                throw FileSystemException(from.toString(), to.toString(), "being used by another process")
            }
            Files.move(from, to, REPLACE_EXISTING, ATOMIC_MOVE)
        }
        AtomicFile(file).write("old")
        atomic.write("new")
        atomic.read() shouldBe "new"
    }

    @Test
    fun `a rename refused every time keeps the old content and reports it`() {
        val atomic = AtomicFile(file) { _, to -> throw AccessDeniedException(to.toString()) }
        AtomicFile(file).write("old")
        val stream = atomic.startWrite()
        stream.write("new".toByteArray())
        shouldThrow<AccessDeniedException> { atomic.finishWrite(stream) }
        atomic.failWrite(stream)
        atomic.read() shouldBe "old"
    }
}

/**
 * The emulated Android looper must not lose a message posted while its loop is between checking
 * the queue and waiting. Such a lost wake left the loop asleep with the message queued: on the
 * main looper that froze whatever coroutine resumed through Dispatchers.Main.
 */
class LooperWakeTest {

    @Test
    fun `a wake before the poll is kept`() {
        val ptr = ShadowPausedMessageQueue.nativeInit()
        try {
            ShadowPausedMessageQueue.nativeWake(ptr)
            val polled = CompletableFuture.runAsync { ShadowPausedMessageQueue.nativePollOnce(ptr, -1) }
            runCatching { polled.get(5, TimeUnit.SECONDS) }.isSuccess shouldBe true
        } finally {
            ShadowPausedMessageQueue.nativeDestroy(ptr)
        }
    }

    @Test
    fun `messages posted from another thread always run`() {
        val ready = CompletableFuture<Looper>()
        val thread = Thread {
            Looper.prepare()
            ready.complete(Looper.myLooper()!!)
            Looper.loop()
        }.apply {
            isDaemon = true
            start()
        }
        val looper = ready.get(5, TimeUnit.SECONDS)
        val handler = Handler(looper)
        try {
            repeat(20_000) {
                val ran = CountDownLatch(1)
                handler.post { ran.countDown() }
                ran.await(5, TimeUnit.SECONDS) shouldBe true
            }
        } finally {
            looper.quit()
            thread.join(5_000)
        }
    }

    @Test
    fun `an Error thrown by a message does not end the loop`() {
        val ready = CompletableFuture<Looper>()
        val thread = Thread {
            Looper.prepare()
            ready.complete(Looper.myLooper()!!)
            Looper.loop()
        }.apply {
            isDaemon = true
            start()
        }
        val looper = ready.get(5, TimeUnit.SECONDS)
        val handler = Handler(looper)
        try {
            // What an extension built against a newer API throws from a callback
            handler.post { throw NoSuchMethodError("probe") }
            handler.post { throw StackOverflowError("probe") }
            val ran = CountDownLatch(1)
            handler.post { ran.countDown() }
            ran.await(5, TimeUnit.SECONDS) shouldBe true
            thread.isAlive shouldBe true
        } finally {
            looper.quit()
            thread.join(5_000)
        }
    }

    @Test
    fun `an OutOfMemoryError from a message still ends the loop, for the crash reporter`() {
        val ready = CompletableFuture<Looper>()
        val thrown = CompletableFuture<Throwable>()
        val thread = Thread {
            Looper.prepare()
            ready.complete(Looper.myLooper()!!)
            Looper.loop()
        }.apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, e -> thrown.complete(e) }
            start()
        }
        val looper = ready.get(5, TimeUnit.SECONDS)
        Handler(looper).post { throw OutOfMemoryError("probe") }
        (thrown.get(5, TimeUnit.SECONDS) is OutOfMemoryError) shouldBe true
        thread.join(5_000)
        thread.isAlive shouldBe false
    }

    @Test
    fun `HandlerThread quit ends the thread`() {
        val thread = HandlerThread("probe").apply { start() }
        (thread.looper != null) shouldBe true
        thread.quit() shouldBe true
        thread.join(5_000)
        thread.isAlive shouldBe false
    }

    @Test
    fun `an interrupted HandlerThread ends`() {
        val thread = HandlerThread("probe-interrupt").apply { start() }
        try {
            val ran = CountDownLatch(1)
            Handler(thread.looper).post { ran.countDown() }
            ran.await(5, TimeUnit.SECONDS) shouldBe true
            thread.interrupt()
            thread.join(5_000)
            thread.isAlive shouldBe false
        } finally {
            thread.quit()
        }
    }

    @Test
    fun `a HandlerThread interrupted before its looper is ready still ends`() {
        repeat(200) { i ->
            val thread = HandlerThread("probe-early-interrupt-$i").apply { start() }
            thread.interrupt()
            thread.join(5_000)
            thread.isAlive shouldBe false
        }
    }

    @Test
    fun `an interrupt does not make the looper spin`() {
        val ready = CompletableFuture<Looper>()
        val thread = Thread {
            Looper.prepare()
            ready.complete(Looper.myLooper()!!)
            Looper.loop()
        }.apply {
            isDaemon = true
            start()
        }
        val looper = ready.get(5, TimeUnit.SECONDS)
        val handler = Handler(looper)
        try {
            thread.interrupt()
            repeat(100) {
                val ran = CountDownLatch(1)
                handler.post { ran.countDown() }
                ran.await(5, TimeUnit.SECONDS) shouldBe true
            }
            // The looper has waited between those messages, and the first wait consumed the interrupt.
            // Restored after each wait, it would end every later wait at once (the spin), and a
            // message would run with it still set
            val interrupted = CompletableFuture<Boolean>()
            handler.post { interrupted.complete(Thread.currentThread().isInterrupted) }
            interrupted.get(5, TimeUnit.SECONDS) shouldBe false
        } finally {
            looper.quit()
            thread.join(5_000)
        }
    }

    @Test
    fun `an interrupted poll clears the interrupt, so the next poll waits for a wake`() {
        val ptr = ShadowPausedMessageQueue.nativeInit()
        try {
            val polling = Thread {
                Thread.currentThread().interrupt()
                // The interrupt ends this one, as on Android
                ShadowPausedMessageQueue.nativePollOnce(ptr, -1)
                ShadowPausedMessageQueue.nativePollOnce(ptr, -1)
            }.apply {
                isDaemon = true
                start()
            }
            // Restored, the interrupt would end the second poll at once too, and every later one: a
            // looper that spins
            Await.until("the poll to wait", state = { polling.state }) {
                polling.state == Thread.State.WAITING || !polling.isAlive
            }
            polling.isAlive shouldBe true
            polling.isInterrupted shouldBe false
            ShadowPausedMessageQueue.nativeWake(ptr)
            polling.join(5_000)
            polling.isAlive shouldBe false
        } finally {
            ShadowPausedMessageQueue.nativeDestroy(ptr)
        }
    }
}

class WakeLocksTest {
    private val held = AtomicInteger()
    private val released = AtomicInteger()
    private val original = holdSystemAwake

    @BeforeEach
    fun fake() {
        holdSystemAwake = {
            held.incrementAndGet()
            KeepAwake.Lock { released.incrementAndGet() }
        }
    }

    @AfterEach
    fun restore() {
        holdSystemAwake = original
    }

    @Test
    fun `a partial wake lock keeps the computer awake until it is released`() {
        val lock = mockk<Context>(relaxed = true).createPartialWakeLock("sync")
        lock.isHeld shouldBe true
        held.get() shouldBe 1
        lock.release()
        lock.isHeld shouldBe false
        released.get() shouldBe 1
        lock.release() // a second release, as the sync may do, is ignored
        released.get() shouldBe 1
    }
}

class RxAwaitSingleTest {

    @Test
    fun `awaitSingle returns the single item`() {
        runBlocking { Observable.just(42).awaitSingle() } shouldBe 42
    }

    @Test
    fun `awaitSingle keeps the binary name extensions link against`() {
        val method = Class.forName("eu.kanade.tachiyomi.util.RxExtensionKt")
            .getMethod("awaitSingle", Observable::class.java, Continuation::class.java)
        method.name shouldBe "awaitSingle"
    }
}

class SparseArrayTest {

    @Test
    fun `keeps keys in order like Android`() {
        val array = SparseArray<String>()
        array.put(5, "five")
        array.put(1, "one")
        array.put(5, "FIVE")
        array.size() shouldBe 2
        array.keyAt(0) shouldBe 1
        array.valueAt(1) shouldBe "FIVE"
        array.get(2) shouldBe null
        val seen = mutableListOf<Int>()
        array.forEach { key, _ -> seen += key }
        seen shouldBe listOf(1, 5)
    }
}

class UpdateMangaFromRemoteTest {
    private val manga = Manga.create().copy(id = 1)

    @Test
    fun `cancelling a refresh is passed on, not returned as a failure`() {
        val refreshManga = mockk<RefreshManga> {
            coEvery { this@mockk.invoke(any(), any(), any(), any(), any(), any()) } throws CancellationException("stopped")
        }
        val update = UpdateMangaFromRemote(sourceManager = mockk(), refreshManga = refreshManga)
        shouldThrow<CancellationException> {
            runBlocking { update(source = mockk(), manga = manga) }
        }
    }

    @Test
    fun `a failing refresh still comes back as a failure`() {
        val refreshManga = mockk<RefreshManga> {
            coEvery { this@mockk.invoke(any(), any(), any(), any(), any(), any()) } throws java.io.IOException("offline")
        }
        val result = runBlocking { UpdateMangaFromRemote(mockk(), refreshManga)(source = mockk(), manga = manga) }
        result.exceptionOrNull().shouldBeInstanceOf<java.io.IOException>()
    }
}
