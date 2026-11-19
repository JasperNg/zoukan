package zoukan.desktop.update

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.Platform
import zoukan.desktop.app.Version
import zoukan.desktop.secrets.SecretStore
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UpdateCheckerTest {
    private val releases = """
        [
          {"tag_name": "desktop-v1.3.0", "name": "Draft", "draft": true, "prerelease": false, "html_url": "d", "assets": []},
          {"tag_name": "desktop-v1.2.0", "name": "Zoukan 1.2.0 beta", "draft": false, "prerelease": true, "html_url": "b", "assets": []},
          {"tag_name": "v1.15.0", "name": "Komikku for Android", "draft": false, "prerelease": false, "html_url": "a", "assets": []},
          {"tag_name": "desktop-v1.1.0", "name": "Zoukan 1.1.0", "body": "Fixes", "draft": false, "prerelease": false,
           "html_url": "https://github.com/o/r/releases/tag/desktop-v1.1.0",
           "assets": [
             {"name": "Zoukan-1.1.0-windows-x64.msi", "url": "https://api.github.com/repos/o/r/releases/assets/1", "size": 100,
              "digest": "sha256:abc123"},
             {"name": "Zoukan-1.1.0-macos-arm64.dmg", "url": "https://api.github.com/repos/o/r/releases/assets/2", "size": 200}
           ]},
          {"tag_name": "desktop-v1.0.1", "name": "", "draft": false, "prerelease": false, "html_url": "x", "assets": []}
        ]
    """.trimIndent()

    private val v100 = Version(listOf(1, 0, 0))

    @Test
    fun `picks the newest published desktop release`() {
        val release = UpdateChecker.select(releases, v100, Platform.WINDOWS)!!
        release.version shouldBe Version(listOf(1, 1, 0))
        release.name shouldBe "Zoukan 1.1.0"
        release.notes shouldBe "Fixes"
        release.asset!!.name shouldBe "Zoukan-1.1.0-windows-x64.msi"
        release.asset!!.apiUrl shouldBe "https://api.github.com/repos/o/r/releases/assets/1"
        release.asset!!.sha256 shouldBe "abc123"
    }

    @Test
    fun `picks the installer for the platform`() {
        UpdateChecker.select(releases, v100, Platform.MACOS)!!.asset!!.name shouldBe "Zoukan-1.1.0-macos-arm64.dmg"
        UpdateChecker.select(releases, v100, Platform.MACOS)!!.asset!!.sha256 shouldBe null
        UpdateChecker.select(releases, v100, Platform.LINUX)!!.asset shouldBe null
    }

    @Test
    fun `test releases only when asked`() {
        UpdateChecker.select(releases, v100, Platform.WINDOWS, includePrereleases = true)!!.version shouldBe Version(listOf(1, 2, 0))
    }

    @Test
    fun `nothing when up to date`() {
        UpdateChecker.select(releases, Version(listOf(1, 1, 0)), Platform.WINDOWS) shouldBe null
        UpdateChecker.select("[]", v100, Platform.WINDOWS) shouldBe null
    }

    @Test
    fun `an untitled release gets a name`() {
        UpdateChecker.select(releases, Version(listOf(1, 0, 0)), Platform.WINDOWS)
        val only = """[{"tag_name": "desktop-v1.0.1", "name": "", "html_url": "x", "assets": []}]"""
        UpdateChecker.select(only, v100, Platform.WINDOWS)!!.name shouldBe "Zoukan 1.0.1"
    }

    @Test
    fun `reads a release's notes for What's new`() {
        val body = """{"tag_name": "desktop-v1.1.0", "name": "", "body": "## Fixes\n- One", "html_url": "https://github.com/o/r/releases/tag/desktop-v1.1.0"}"""
        val notes = UpdateChecker.parseNotes(body, Version(listOf(1, 1, 0)))
        notes.name shouldBe "Zoukan 1.1.0"
        notes.body shouldBe "## Fixes\n- One"
        notes.pageUrl shouldBe "https://github.com/o/r/releases/tag/desktop-v1.1.0"
    }

    private fun answering(body: String) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()

    /** Answers with status 200 that are not GitHub's JSON: an empty body, a captive portal's page, a release without its tag. */
    private val notReleases = listOf("", "<html>Sign in to the Wi-Fi</html>", """{"name": "no tag"}""", """[{"name": 1}]""", "{")

    @Test
    fun `release notes from an answer that is not a release are a failed result, not an exception`(): Unit = runBlocking {
        for (body in notReleases) {
            val checker = UpdateChecker(answering(body), MemorySecrets(), repo = "o/r", platform = Platform.WINDOWS)
            checker.releaseNotes(Version(listOf(9, 9, 9))).exceptionOrNull().shouldBeInstanceOf<IOException>()
        }
    }

    @Test
    fun `an update check whose answer is not a release list is a failed result, not an exception`(): Unit = runBlocking {
        for (body in notReleases) {
            val checker = UpdateChecker(answering(body), MemorySecrets(), repo = "o/r", platform = Platform.WINDOWS)
            checker.check().shouldBeInstanceOf<UpdateChecker.Result.Failed>()
        }
    }

    @Test
    fun `a cancelled request stays cancelled`(): Unit = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw CancellationException("cancelled") }.build()
        val checker = UpdateChecker(client, MemorySecrets(), repo = "o/r", platform = Platform.WINDOWS)
        shouldThrow<CancellationException> { checker.releaseNotes(Version(listOf(9, 9, 9))) }
        shouldThrow<CancellationException> { checker.check() }
    }

    private class MemorySecrets(private val map: MutableMap<String, String> = mutableMapOf()) : SecretStore {
        override val name = "memory"
        override fun get(key: String) = map[key]
        override fun set(key: String, value: String) {
            map[key] = value
        }
        override fun delete(key: String) {
            map.remove(key)
        }
    }

    private fun recordingClient(seen: MutableList<Request>) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            seen += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("x".toResponseBody()).build()
        }.build()

    @Test
    fun `an asset address that is not https on the GitHub API gets no token`(@TempDir dir: File): Unit = runBlocking {
        val seen = mutableListOf<Request>()
        val checker = UpdateChecker(
            recordingClient(seen),
            MemorySecrets(mutableMapOf(UpdateChecker.TOKEN_KEY to "secret-token")),
            repo = "o/r",
        )
        for (url in listOf("http://api.github.com/repos/o/r/releases/assets/1", "https://evil.example/asset")) {
            runCatching { checker.download(UpdateChecker.Asset("Zoukan.msi", url, 1, null), dir) }
        }
        seen.forEach { it.header("Authorization") shouldBe null }
    }

    @Test
    fun `an asset address on the GitHub API over https is downloaded with the token`(@TempDir dir: File): Unit = runBlocking {
        val seen = mutableListOf<Request>()
        val checker = UpdateChecker(
            recordingClient(seen),
            MemorySecrets(mutableMapOf(UpdateChecker.TOKEN_KEY to "secret-token")),
            repo = "o/r",
        )
        checker.download(UpdateChecker.Asset("Zoukan.msi", "https://api.github.com/repos/o/r/releases/assets/1", 1, null), dir)
        seen.map { it.header("Authorization") } shouldBe listOf("Bearer secret-token")
    }
}

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
    fun `a fatal error in the check or the download goes on up and leaves no state stuck`(): Unit = runBlocking {
        val failing = UpdateStates(check = { throw OutOfMemoryError() }, download = { _, _ -> error("no") })
        shouldThrow<OutOfMemoryError> { failing.check(manual = true) }
        failing.state.value shouldBe UpdateStates.State.Idle

        // The secret store's native library failing to load is a failed check, as before
        val unlinked = UpdateStates(check = { throw UnsatisfiedLinkError("no jnidispatch") }, download = { _, _ -> error("no") })
        unlinked.check(manual = true) shouldBe UpdateChecker.Result.Failed("no jnidispatch")
        // A stack overflow is not rethrown on this path: it is a failed check that shows
        val overflow = UpdateStates(check = { throw StackOverflowError("deep") }, download = { _, _ -> error("no") })
        overflow.check(manual = true) shouldBe UpdateChecker.Result.Failed("deep")
        overflow.state.value shouldBe UpdateStates.State.Result(UpdateChecker.Result.Failed("deep"))

        val states = UpdateStates(check = { available }, download = { _, _ -> throw OutOfMemoryError("heap") })
        states.check(manual = true)
        shouldThrow<OutOfMemoryError> { states.download(release) }
        states.state.value shouldBe UpdateStates.State.Failed("Download failed: heap", release)
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

/**
 * The update check against the real GitHub releases: an app at [FROM] finds a newer release,
 * downloads this platform's installer and its `.sig`, and the release signature verifies under
 * the public key committed in `zoukan-release-key.pem`; a copy with one byte flipped is refused.
 *
 * Needs the internet and downloads one installer (about 300 MB), so it runs only when asked:
 *   ZOUKAN_LIVE=true ./gradlew :desktop:test --tests "*UpdateLiveTest" --rerun
 * (or -Dzoukan.live=true on the test JVM). `ZOUKAN_LIVE_UPDATE_FROM=1.0.6` sets the version the
 * check runs as. It uses an in-memory secret store (no GitHub token, nothing read from the OS
 * store) and a temporary folder: no app data is touched.
 *
 * Releases published before the release signature have no `.sig`. For those the test checks
 * that the app refuses the installer (fails closed) and then skips the signature checks.
 */
class UpdateLiveTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the newest release is found, downloaded and its release signature verifies`(): Unit = runBlocking {
        assumeTrue(System.getProperty("zoukan.live") == "true" || System.getenv("ZOUKAN_LIVE") == "true")
        val from = Version.parse(System.getenv("ZOUKAN_LIVE_UPDATE_FROM") ?: FROM).shouldNotBeNull()
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.MINUTES).build()
        val checker = UpdateChecker(client, NoSecrets, current = from)

        val result = checker.check()
        println("Update check from $from: $result")
        val release = result.shouldBeInstanceOf<UpdateChecker.Result.Available>().release
        (release.version > from) shouldBe true
        val asset = release.asset.shouldNotBeNull()
        println("Installer ${asset.name}, ${asset.size} bytes, sha256 ${asset.sha256}, signature ${asset.signatureApiUrl != null}")
        asset.sha256.shouldNotBeNull()

        val installer = checker.download(asset, dir)
        installer.length() shouldBe asset.size
        val signature = checker.downloadSignature(asset, dir)
        val key = ReleaseSignature.builtIn()

        if (signature == null) {
            // A release from before the release signature: the app must refuse it
            key.verify(installer, null).shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()
            ReleaseSignature(publicKeyPem = null, packaged = true).verify(installer, null)
                .shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()
            println("${release.tag} has no release signature: refused, as an unsigned installer must be")
            assumeTrue(false, "${release.tag} carries no .sig; the signature checks need a signed release")
        }
        signature!!.length() shouldBe ReleaseSignature.SIGNATURE_BYTES

        val verdict = key.verify(installer, signature)
        println("Release signature of ${installer.name}: $verdict")
        verdict.shouldBeInstanceOf<InstallerSignature.Verdict.Trusted>()

        // The same file with one byte flipped, under the same name: the SHA-256 in the statement changes
        val tampered = File(dir, "tampered").apply { mkdirs() }.resolve(installer.name)
        installer.copyTo(tampered)
        java.io.RandomAccessFile(tampered, "rw").use { file ->
            val at = file.length() / 2
            file.seek(at)
            val b = file.read()
            file.seek(at)
            file.write(b xor 0x01)
        }
        val tamperedVerdict = key.verify(tampered, signature)
        println("Release signature of the tampered copy: $tamperedVerdict")
        tamperedVerdict.shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()

        // A signature that is not the release's (one byte flipped) is refused as well
        val badSignature = File(dir, "bad.sig").apply {
            writeBytes(signature.readBytes().also { it[0] = (it[0].toInt() xor 0x01).toByte() })
        }
        key.verify(installer, badSignature).shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()
    }

    private object NoSecrets : SecretStore {
        override val name = "none"
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) = Unit
        override fun delete(key: String) = Unit
    }

    private companion object {
        const val FROM = "1.0.6"
    }
}
