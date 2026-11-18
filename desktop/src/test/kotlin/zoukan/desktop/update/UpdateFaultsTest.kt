package zoukan.desktop.update

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import zoukan.desktop.Platform
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.Version
import zoukan.desktop.compat.localized
import zoukan.desktop.e2e.FixtureFailure
import zoukan.desktop.e2e.FixtureReleases
import zoukan.desktop.e2e.FixtureServer
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.testing.fakes.FailingSecretStore
import zoukan.desktop.testing.fakes.FileFaults
import zoukan.desktop.testing.fakes.MemorySecretStore
import zoukan.i18n.desktop.DMR
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The update check and download (the states Settings > About shows) under injected faults: the
 * release list, the installer and its signature failing or arriving damaged, a download folder that
 * cannot be made, and a locked secret store. For each: the state the UI shows says it failed (and
 * keeps the release, so it can be downloaded again), the next check or download after the fault is
 * healed works, and no half-downloaded or unverified file is kept, nor an earlier download lost.
 */
class UpdateFaultsTest {
    @TempDir
    lateinit var temp: File

    private val server = FixtureServer()
    private val releases = FixtureReleases(server, REPO)
    private val key = TestReleaseKey()
    private val installer = ByteArray(300_000) { (it % 241).toByte() }
    private val dir get() = File(temp, "updates")

    @AfterEach
    fun close() {
        releases.close()
        server.close()
    }

    private fun checker(secrets: SecretStore = MemorySecretStore()) = UpdateChecker(
        releases.client(OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()),
        secrets,
        repo = REPO,
        current = Version.parse("1.0.0")!!,
        platform = Platform.WINDOWS,
    )

    private fun states(checker: UpdateChecker = checker(), into: File = dir) = UpdateStates(
        check = { checker.check() },
        download = { asset, progress -> Updates.downloadVerified(checker, asset, into, key.verifier, InstallerSignature(packaged = false), progress) },
    )

    /** Publishes 2.0.0 with a signed installer; returns the paths of the installer and its signature. */
    private fun publish(): Pair<String, String> {
        releases.publish(
            releases.release(
                AppVersion.TAG_PREFIX + "2.0.0",
                "Zoukan 2.0.0",
                "Notes",
                assets = listOf(FixtureReleases.Asset(NAME, installer), FixtureReleases.Asset("$NAME.sig", key.sign(NAME, installer), sha256 = null)),
            ),
        )
        val asset = runBlocking { checker().check() }.shouldBeInstanceOf<UpdateChecker.Result.Available>().release.asset!!
        return URI(asset.apiUrl).path to URI(asset.signatureApiUrl!!).path
    }

    private fun UpdateStates.available(): UpdateChecker.Release = runBlocking {
        check(manual = true).shouldBeInstanceOf<UpdateChecker.Result.Available>().release
    }

    /** The download failed in a way the UI shows, can be tried again, and left nothing in the folder. */
    private fun UpdateStates.failedCleanly(release: UpdateChecker.Release, downloaded: File?) {
        downloaded.shouldBeNull()
        val failed = state.value.shouldBeInstanceOf<UpdateStates.State.Failed>()
        failed.message.shouldNotBeBlank()
        failed.release shouldBe release
        dir.listFiles().orEmpty().toList().shouldBeEmpty()
    }

    /** After the fault is healed, the same release downloads and is kept, verified. */
    private fun UpdateStates.downloadWorks(release: UpdateChecker.Release) {
        server.clearFailures()
        val file = runBlocking { download(release) }.shouldNotBeNull()
        state.value.shouldBeInstanceOf<UpdateStates.State.Downloaded>().file shouldBe file
        file.readBytes() shouldBe installer
        dir.list()!!.sorted() shouldBe listOf(NAME, "$NAME.sig")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("installerFaults")
    fun `an installer download that fails or arrives damaged is shown as failed, keeps nothing, and downloads again`(
        name: String,
        failure: FixtureFailure,
    ) {
        val (installerPath, _) = publish()
        val states = states()
        val release = states.available()
        server.fail(installerPath, failure)

        states.failedCleanly(release, runBlocking { states.download(release) })

        states.downloadWorks(release)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("signatureFaults")
    fun `a release signature that fails or is too large rejects the installer and keeps nothing`(name: String, failure: FixtureFailure) {
        val (_, signaturePath) = publish()
        val states = states()
        val release = states.available()
        server.fail(signaturePath, failure)

        states.failedCleanly(release, runBlocking { states.download(release) })

        states.downloadWorks(release)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("feedFaults")
    fun `a release list that fails or is not JSON is a failed check, and the next check finds the update`(name: String, failure: FixtureFailure) {
        publish()
        val states = states()
        server.fail(releases.releasesPath, failure)

        val result = runBlocking { states.check(manual = true) }

        result.shouldBeInstanceOf<UpdateChecker.Result.Failed>().message.shouldNotBeBlank()
        states.state.value shouldBe UpdateStates.State.Result(result!!)
        server.clearFailures()
        states.available().version.toString() shouldBe "2.0.0"
    }

    @Test
    fun `a failed download of the same version keeps the installer already downloaded`() {
        val (installerPath, _) = publish()
        val checker = checker()
        val asset = runBlocking { checker.check() }.shouldBeInstanceOf<UpdateChecker.Result.Available>().release.asset!!
        val first = runBlocking { Updates.downloadVerified(checker, asset, dir, key.verifier, InstallerSignature(packaged = false)) }
        val signature = File(dir, "$NAME.sig").readBytes()
        server.fail(installerPath, FixtureFailure.Truncate(keepBytes = 100_000))

        shouldThrow<IOException> {
            runBlocking { Updates.downloadVerified(checker, asset, dir, key.verifier, InstallerSignature(packaged = false)) }
        }

        first.readBytes() shouldBe installer
        File(dir, "$NAME.sig").readBytes() shouldBe signature
        dir.list()!!.sorted() shouldBe listOf(NAME, "$NAME.sig")
    }

    @Test
    fun `a file where the download folder should be is shown as a failed download, and a retry after it is gone works`() {
        publish()
        val blocker = FileFaults.fileInTheWay(dir)
        val states = states()
        val release = states.available()

        val downloaded = runBlocking { states.download(release) }

        downloaded.shouldBeNull()
        states.state.value.shouldBeInstanceOf<UpdateStates.State.Failed>().release shouldBe release
        blocker.readText() shouldBe "A file where a folder is needed"

        blocker.delete() shouldBe true
        states.downloadWorks(release)
    }

    @Test
    fun `a locked secret store does not stop the update check, which goes on without a token`() {
        publish()
        val store = FailingSecretStore().apply { backing.values[UpdateChecker.TOKEN_KEY] = "a token" }
        store.failAll(FailingSecretStore.Failure.LOCKED)
        val states = states(checker(store))

        states.available().version.toString() shouldBe "2.0.0"
        // Nothing is written to the store, so a locked store loses no token
        store.attemptedWrites.shouldBeEmpty()
        store.backing.values[UpdateChecker.TOKEN_KEY] shouldBe "a token"
    }

    @Test
    fun `a private repository with a locked secret store says the store could not be read, not to add a token`() {
        publish()
        // GitHub answers 404 for a private repository read without its token (the list and each release)
        server.fail(releases.releasesPath, FixtureFailure.Status(404))
        val store = FailingSecretStore().apply { backing.values[UpdateChecker.TOKEN_KEY] = "a token" }
        store.failAll(FailingSecretStore.Failure.LOCKED)
        val checker = checker(store)

        val check = runBlocking { checker.check() }.shouldBeInstanceOf<UpdateChecker.Result.Failed>().message
        val notes = runBlocking { checker.releaseNotes(Version.parse("2.0.0")!!) }.exceptionOrNull()!!.message!!

        for (message in listOf(check, notes)) {
            message shouldContain localized(DMR.strings.could_not_use_the_secret_store)
            message shouldNotContain "add a GitHub token"
        }
        store.attemptedWrites.shouldBeEmpty()
    }

    @Test
    fun `a private repository with no token stored still says to add one`() {
        publish()
        server.fail(releases.releasesPath, FixtureFailure.Status(404))
        val checker = checker(MemorySecretStore())

        runBlocking { checker.check() }.shouldBeInstanceOf<UpdateChecker.Result.Failed>().message shouldContain "add a GitHub token"
        runBlocking { checker.releaseNotes(Version.parse("2.0.0")!!) }.exceptionOrNull()!!.message!! shouldContain "add a GitHub token"
    }

    private companion object {
        const val REPO = "zoukan-test/zoukan"
        const val NAME = "Zoukan-2.0.0.msi"

        @JvmStatic
        fun installerFaults() = listOf(
            arrayOf("HTTP 500", FixtureFailure.Status(500)),
            arrayOf("connection cut mid-body", FixtureFailure.Truncate(keepBytes = 100_000)),
            arrayOf("connection closed without an answer", FixtureFailure.Drop),
            arrayOf("corrupt body", FixtureFailure.Corrupt(fromByte = 5000)),
            arrayOf("more bytes than the release lists", FixtureFailure.Oversize(extraBytes = 4096)),
            arrayOf("an HTML error page", FixtureFailure.Answer(200, "text/html", "<html>Rate limited</html>".toByteArray())),
        )

        @JvmStatic
        fun signatureFaults() = listOf(
            arrayOf("HTTP 502", FixtureFailure.Status(502)),
            arrayOf("connection cut mid-body", FixtureFailure.Truncate(keepBytes = 10)),
            arrayOf("larger than a signature", FixtureFailure.Oversize(extraBytes = 100)),
            arrayOf("corrupt", FixtureFailure.Corrupt()),
        )

        @JvmStatic
        fun feedFaults() = listOf(
            arrayOf("HTTP 503", FixtureFailure.Status(503)),
            arrayOf("an HTML page", FixtureFailure.Answer(200, "text/html", "<html><body>Maintenance</body></html>".toByteArray())),
            arrayOf("cut mid-body", FixtureFailure.Truncate(keepBytes = 20)),
            arrayOf("corrupt", FixtureFailure.Corrupt()),
            arrayOf("connection closed without an answer", FixtureFailure.Drop),
        )
    }
}
