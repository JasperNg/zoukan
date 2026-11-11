package zoukan.desktop.update

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.app.Version
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class UpdateStatesTest {
    @TempDir
    lateinit var dir: File

    private val release = UpdateChecker.Release(
        version = Version(listOf(1, 1, 0)),
        tag = "desktop-v1.1.0",
        name = "Zoukan 1.1.0",
        notes = "",
        pageUrl = "",
        asset = UpdateChecker.Asset("Zoukan-1.1.0-windows-x64.msi", "https://example.invalid/1", 10, null),
    )
    private val available = UpdateChecker.Result.Available(release)

    @Test
    fun `a second check or download while one runs does nothing`(): Unit = runBlocking {
        val checks = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val downloads = AtomicInteger()
        val downloadGate = CompletableDeferred<Unit>()
        val states = UpdateStates(
            check = {
                checks.incrementAndGet()
                gate.await()
                available
            },
            download = { _, _ ->
                downloads.incrementAndGet()
                downloadGate.await()
                File(dir, "Zoukan.msi").apply { writeText("msi") }
            },
        )
        val first = async { states.check(manual = false) }
        yield()
        states.state.value shouldBe UpdateStates.State.Checking
        states.check(manual = true) shouldBe null
        gate.complete(Unit)
        first.await() shouldBe available
        checks.get() shouldBe 1
        states.state.value shouldBe UpdateStates.State.Result(available)

        val download = async { states.download(release) }
        yield()
        states.state.value.shouldBeInstanceOf<UpdateStates.State.Downloading>()
        states.download(release) shouldBe null
        states.check(manual = true) shouldBe null
        downloadGate.complete(Unit)
        download.await()!!.name shouldBe "Zoukan.msi"
        downloads.get() shouldBe 1
        states.state.value.shouldBeInstanceOf<UpdateStates.State.Downloaded>()
    }

    @Test
    fun `download only from an available release`(): Unit = runBlocking {
        val states = UpdateStates(check = { UpdateChecker.Result.UpToDate }, download = { _, _ -> error("not called") })
        states.download(release) shouldBe null
        states.state.value shouldBe UpdateStates.State.Idle
        states.check(manual = true)
        states.download(release) shouldBe null
        states.state.value shouldBe UpdateStates.State.Result(UpdateChecker.Result.UpToDate)
    }

    @Test
    fun `the automatic check keeps a downloaded update, a manual one finding it again too`(): Unit = runBlocking {
        var result: UpdateChecker.Result = available
        val file = File(dir, "Zoukan.msi").apply { writeText("msi") }
        val states = UpdateStates(check = { result }, download = { _, _ -> file })
        states.check(manual = true)
        states.download(release)
        val downloaded = UpdateStates.State.Downloaded(release, file)
        states.state.value shouldBe downloaded

        states.check(manual = false) shouldBe null
        states.state.value shouldBe downloaded

        states.check(manual = true) shouldBe available
        states.state.value shouldBe downloaded

        val newer = release.copy(version = Version(listOf(1, 2, 0)), tag = "desktop-v1.2.0")
        result = UpdateChecker.Result.Available(newer)
        states.check(manual = true)
        states.state.value shouldBe UpdateStates.State.Result(result)
    }

    @Test
    fun `a failed download can be tried again`(): Unit = runBlocking {
        var fail = true
        val states = UpdateStates(
            check = { available },
            download = { _, _ ->
                if (fail) throw IOException("connection reset")
                File(dir, "Zoukan.msi").apply { writeText("msi") }
            },
        )
        states.check(manual = true)
        states.download(release) shouldBe null
        states.state.value shouldBe UpdateStates.State.Failed("Download failed: connection reset", release)
        fail = false
        states.download(release)!!.name shouldBe "Zoukan.msi"
    }

    @Test
    fun `cancelling goes back to where it started`(): Unit = runBlocking {
        val states = UpdateStates(
            check = { awaitCancellation() },
            download = { _, _ -> awaitCancellation() },
        )
        val check = launch { states.check(manual = true) }
        yield()
        states.state.value shouldBe UpdateStates.State.Checking
        check.cancelAndJoin()
        states.state.value shouldBe UpdateStates.State.Idle

        val ready = UpdateStates(check = { available }, download = { _, _ -> awaitCancellation() })
        ready.check(manual = true)
        val download = launch { ready.download(release) }
        yield()
        ready.state.value.shouldBeInstanceOf<UpdateStates.State.Downloading>()
        download.cancelAndJoin()
        ready.state.value shouldBe UpdateStates.State.Result(available)
    }

    @Test
    fun `a failing check is a result, not a crash`(): Unit = runBlocking {
        val states = UpdateStates(check = { throw IllegalStateException("bad json") }, download = { _, _ -> error("no") })
        states.check(manual = true) shouldBe UpdateChecker.Result.Failed("bad json")
        states.state.value shouldBe UpdateStates.State.Result(UpdateChecker.Result.Failed("bad json"))
    }

    @Test
    fun `a rejected installer goes to failed, to download again`(): Unit = runBlocking {
        val file = File(dir, "update.msi").apply { writeText("x") }
        val states = UpdateStates(check = { available }, download = { _, _ -> file })
        states.check(manual = true)
        states.download(release)

        states.installRejected(File(dir, "other.msi"), "no") shouldBe false
        states.state.value shouldBe UpdateStates.State.Downloaded(release, file)

        states.installRejected(file, "Not signed by Zoukan's publisher") shouldBe true
        states.state.value shouldBe UpdateStates.State.Failed("Not signed by Zoukan's publisher", release)
        states.installRejected(file, "again") shouldBe false

        states.download(release) shouldBe file
        states.state.value shouldBe UpdateStates.State.Downloaded(release, file)
    }
}
