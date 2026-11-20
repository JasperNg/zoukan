package zoukan.desktop.update

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_download
import tachiyomi.i18n.action_install
import tachiyomi.i18n.check_for_updates
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.FileRequest
import zoukan.desktop.app.SystemEffects
import zoukan.desktop.app.Version
import zoukan.desktop.compat.localized
import zoukan.desktop.e2e.FixtureFailure
import zoukan.desktop.e2e.FixtureReleases
import zoukan.desktop.e2e.FixtureServer
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FileSystemSandbox
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testing.fakes.FailingSecretStore
import zoukan.desktop.testing.fakes.FileFaults
import zoukan.desktop.testing.fakes.MemorySecretStore
import zoukan.desktop.testkit.TestPreferenceStore
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.settings.aboutSettings
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.could_not_use_the_secret_store
import zoukan.i18n.desktop.include_test_releases
import zoukan.i18n.desktop.is_available
import zoukan.i18n.desktop.zoukan_is_the_newest_version
import java.awt.Window
import java.awt.datatransfer.Transferable
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * An update's download and checks offline (Updates.downloadVerified): the release list, the
 * installer and its `.sig` come from the fixture server, at GitHub API addresses, and the release
 * signature is checked with a test key, as a packaged build checks it with the built-in one.
 */
class UpdatesDownloadTest {
    @TempDir
    lateinit var dir: File

    private val server = FixtureServer()
    private val releases = FixtureReleases(server, REPO)
    private val key = TestReleaseKey()
    private val installer = ByteArray(300_000) { (it % 241).toByte() }

    @AfterEach
    fun close() {
        releases.close()
        server.close()
    }

    private object NoSecrets : SecretStore {
        override val name = "none"
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) = Unit
        override fun delete(key: String) = Unit
    }

    private val checker by lazy {
        UpdateChecker(releases.client(OkHttpClient()), NoSecrets, repo = REPO, current = Version.parse("1.0.0")!!, platform = Platform.WINDOWS)
    }

    /** Publishes a 2.0.0 release with [served] as the installer and [signature] as its `.sig`, and checks for it. */
    private fun offered(served: FixtureReleases.Asset, signature: ByteArray?): UpdateChecker.Asset {
        val assets = listOfNotNull(served, signature?.let { FixtureReleases.Asset(NAME + ".sig", it, sha256 = null) })
        releases.publish(releases.release(AppVersion.TAG_PREFIX + "2.0.0", "Zoukan 2.0.0", "Notes", assets = assets))
        val result = runBlocking { checker.check() }
        return result.shouldBeInstanceOf<UpdateChecker.Result.Available>().release.asset!!
    }

    private fun download(asset: UpdateChecker.Asset): File = runBlocking {
        Updates.downloadVerified(checker, asset, dir, key.verifier, InstallerSignature(packaged = false))
    }

    @Test
    fun `an installer signed with the release key is downloaded from the API addresses and kept`() {
        val asset = offered(FixtureReleases.Asset(NAME, installer), key.sign(NAME, installer))
        asset.apiUrl.startsWith(FixtureReleases.API) shouldBe true
        val file = download(asset)
        file.readBytes().contentEquals(installer) shouldBe true
        File(dir, "$NAME.sig").isFile shouldBe true
        server.requests.count { it.startsWith(releases.releasesPath + "/assets/") } shouldBe 2
    }

    @Test
    fun `an installer changed after it was signed is rejected and deleted, though GitHub's checksum matches it`() {
        val tampered = installer.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        // The release JSON lists the tampered file's own SHA-256: only the release signature can tell
        val asset = offered(FixtureReleases.Asset(NAME, tampered), key.sign(NAME, installer))
        shouldThrow<IOException> { download(asset) }.message shouldContain "does not match Zoukan's release key"
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `an installer signed with another key is rejected`() {
        val asset = offered(FixtureReleases.Asset(NAME, installer), TestReleaseKey().sign(NAME, installer))
        shouldThrow<IOException> { download(asset) }.message shouldContain "does not match Zoukan's release key"
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `a release without a signature is rejected`() {
        val asset = offered(FixtureReleases.Asset(NAME, installer), signature = null)
        shouldThrow<IOException> { download(asset) }.message shouldContain "no valid release signature"
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `a download that does not match GitHub's checksum is refused before its signature is fetched`() {
        val asset = offered(FixtureReleases.Asset(NAME, installer, sha256 = TestReleaseKey.sha256(byteArrayOf(1))), key.sign(NAME, installer))
        shouldThrow<IOException> { download(asset) }.message shouldContain "SHA-256 does not match"
        server.requests.count { it.startsWith(releases.releasesPath + "/assets/") } shouldBe 1
        File(dir, NAME).exists() shouldBe false
    }

    @Test
    fun `a new download clears only the app's own old downloads, and never follows a link`() {
        FileSystemSandbox.create(dir).use { sandbox ->
            val updates = File(sandbox.dataFolder, "updates").apply { mkdirs() }
            // The app's own: an older installer, its signature, a half download and a copy install() ran
            val own = listOf("Zoukan-1.9.0.msi", "Zoukan-1.9.0.msi.sig", "Zoukan-1.9.0.msi.part", "Zoukan-1.9.0-arm64.DMG")
                .map { File(updates, it).apply { writeText("old") } }
            val runCopy = File(updates, "run4821937465").apply { mkdirs() }
            File(runCopy, "Zoukan-1.9.0.msi").writeText("old")
            // Another program's files in the folder
            val others = listOf(File(updates, "notes.txt"), File(updates, "other-program/data.bin"), File(updates, "runner/x"))
                .onEach {
                    it.parentFile.mkdirs()
                    it.writeText("not Zoukan's")
                }
            // Links named like the app's files, to a folder and a file of the user's
            val userFile = File(sandbox.userFolder, "kept.msi").apply { writeText("the user's") }
            val links = listOfNotNull(
                sandbox.link(File(updates, "Zoukan-1.8.0.msi"), userFile),
                sandbox.link(File(updates, "run1234"), sandbox.userFolder),
            )
            sandbox.record()

            val asset = offered(FixtureReleases.Asset(NAME, installer), key.sign(NAME, installer))
            val file = runBlocking { Updates.downloadVerified(checker, asset, updates, key.verifier, InstallerSignature(packaged = false)) }

            file.readBytes().contentEquals(installer) shouldBe true
            (own + runCopy + links).filter { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) } shouldBe emptyList()
            others.forEach { it.readText() shouldBe "not Zoukan's" }
            userFile.readText() shouldBe "the user's"
            sandbox.assertNothingOutsideAppChanged()
        }
    }

    private companion object {
        const val REPO = "zoukan-test/zoukan"
        const val NAME = "Zoukan-2.0.0.msi"
    }
}

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
        // A short read timeout, so a stall fails within the test (the app's client waits longer)
        releases.client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()),
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
    fun `the token is read once per request, so the message matches what was sent`() {
        publish()
        server.fail(releases.releasesPath, FixtureFailure.Status(404))
        // The store answers the first read, then locks: the token was sent, so the 404 is GitHub's own
        // answer, and the store is not asked again (on macOS each read may show a Keychain prompt)
        fun store() = FailingSecretStore().apply {
            backing.values[UpdateChecker.TOKEN_KEY] = "a token"
            failOn(FailingSecretStore.Operation.READ, FailingSecretStore.Failure.LOCKED, succeedFirst = 1)
        }

        val check = runBlocking { checker(store()).check() }.shouldBeInstanceOf<UpdateChecker.Result.Failed>().message
        val notes = runBlocking { checker(store()).releaseNotes(Version.parse("2.0.0")!!) }.exceptionOrNull()!!.message!!

        for (message in listOf(check, notes)) {
            message shouldNotContain localized(DMR.strings.could_not_use_the_secret_store)
            message shouldNotContain "add a GitHub token"
        }
    }

    @Test
    fun `a 404 with no token stored reports missing releases without a secret store or token hint`() {
        publish()
        server.fail(releases.releasesPath, FixtureFailure.Status(404))
        val checker = checker(MemorySecretStore())

        val check = runBlocking { checker.check() }.shouldBeInstanceOf<UpdateChecker.Result.Failed>().message
        val notes = runBlocking { checker.releaseNotes(Version.parse("2.0.0")!!) }.exceptionOrNull()!!.message!!

        check shouldBe "No releases found"
        notes shouldBe "No release notes for 2.0.0 on GitHub"
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
            arrayOf("a stall mid-body (the read timeout)", FixtureFailure.Stall(afterBytes = 100_000)),
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
            arrayOf("a stall mid-body (the read timeout)", FixtureFailure.Stall(afterBytes = 10)),
        )
    }
}

/**
 * Isolated About-settings update flow offline: checking with and without test releases, downloading
 * a signed installer via [FixtureReleases] on `https://api.github.com` addresses, verifying its
 * Ed25519 release signature via [Updates.releaseSignatureForTests], and handing the checked file
 * to [SystemEffects.installUpdate].
 */
@OptIn(ExperimentalTestApi::class)
class UpdateInstallFlowTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var dir: File

    private val server = FixtureServer()
    private val releases = FixtureReleases(server)
    private lateinit var dirs: ZoukanDirs

    @BeforeEach
    fun setUp() {
        dirs = ZoukanDirs(dir)
        Injekt.addSingleton(dirs)
        Injekt.addSingleton<PreferenceStore>(TestPreferenceStore())
        Injekt.addSingleton<SecretStore>(MemorySecretStore())
    }

    @AfterEach
    fun tearDown() {
        SystemEffects.current = SystemEffects.Native
        Updates.checkerForTests = null
        Updates.releaseSignatureForTests = null
        releases.close()
        server.close()
    }

    @Test
    fun `check for updates offers a test release only when include test releases is enabled`() {
        val stable = AppVersion.version.toString()
        val next = Version(AppVersion.version.parts.mapIndexed { i, part -> if (i == 0) part + 1 else 0 }).toString()
        releases.publish(
            releases.release(AppVersion.TAG_PREFIX + next, "Zoukan $next beta", "Beta notes", prerelease = true),
            releases.release(AppVersion.TAG_PREFIX + stable, "Zoukan $stable", "Stable notes", prerelease = false),
        )
        Updates.checkerForTests = UpdateChecker(releases.client(OkHttpClient()), Injekt.get())
        val before = Updates.includePrereleases().get()
        try {
            Updates.includePrereleases().set(false)
            runDesktopComposeUiTest {
                setContent { LazyColumn(Modifier.testTag("about-list")) { aboutSettings() } }
                val list = onNodeWithTag("about-list")

                list.performScrollToNode(hasText(localized(MR.strings.check_for_updates)))
                onNodeWithText(localized(MR.strings.check_for_updates)).performClick()
                Await.until("up-to-date result") {
                    Updates.state.value == UpdateStates.State.Result(UpdateChecker.Result.UpToDate)
                }
                waitForIdle()
                onNodeWithText(localized(DMR.strings.zoukan_is_the_newest_version, AppVersion.name)).assertExists()
                server.requests.count { it == releases.releasesPath } shouldBe 1

                list.performScrollToNode(hasText(localized(DMR.strings.include_test_releases)))
                onNodeWithText(localized(DMR.strings.include_test_releases)).performClick()
                Await.until("prereleases preference on") { Updates.includePrereleases().get() }

                list.performScrollToNode(hasText(localized(MR.strings.check_for_updates)))
                onNodeWithText(localized(MR.strings.check_for_updates)).performClick()
                Await.until("available prerelease result") {
                    (Updates.state.value as? UpdateStates.State.Result)?.result is UpdateChecker.Result.Available
                }
                waitForIdle()
                onNodeWithText(localized(DMR.strings.is_available, "Zoukan $next beta"), substring = true).assertExists()
                (Updates.state.value as UpdateStates.State.Result).result
                    .shouldBeInstanceOf<UpdateChecker.Result.Available>().release.version.toString() shouldBe next
                server.requests.count { it == releases.releasesPath } shouldBe 2
            }
        } finally {
            Updates.includePrereleases().set(before)
        }
    }

    @Test
    fun `signed update downloads, passes Ed25519 verification, and hands installer to SystemEffects`() {
        val key = TestReleaseKey()
        val recorder = RecordingEffects()
        val next = Version(AppVersion.version.parts.mapIndexed { i, part -> if (i == 0) part + 2 else 0 }).toString()
        val name = "Zoukan-$next.msi"
        val installer = ByteArray(256_000) { (it % 239).toByte() }
        releases.publish(
            releases.release(
                AppVersion.TAG_PREFIX + next,
                "Zoukan $next",
                "Faster pages",
                assets = listOf(
                    FixtureReleases.Asset(name, installer),
                    FixtureReleases.Asset("$name.sig", key.sign(name, installer), sha256 = null),
                ),
            ),
        )
        Updates.checkerForTests = UpdateChecker(releases.client(OkHttpClient()), Injekt.get(), platform = Platform.WINDOWS)
        Updates.releaseSignatureForTests = key.verifier
        SystemEffects.current = recorder
        val before = Updates.includePrereleases().get()
        try {
            Updates.includePrereleases().set(false)
            runDesktopComposeUiTest {
                setContent { LazyColumn(Modifier.testTag("about-list")) { aboutSettings() } }
                val list = onNodeWithTag("about-list")

                list.performScrollToNode(hasText(localized(MR.strings.check_for_updates)))
                onNodeWithText(localized(MR.strings.check_for_updates)).performClick()
                Await.until("update check result") {
                    (Updates.state.value as? UpdateStates.State.Result)?.result is UpdateChecker.Result.Available
                }
                waitForIdle()
                onNodeWithText(localized(DMR.strings.is_available, "Zoukan $next"), substring = true).assertExists()

                onAllNodes(hasText(localized(MR.strings.action_download)) and hasClickAction()).onFirst().performClick()
                Await.until("update download completion") { Updates.state.value is UpdateStates.State.Downloaded }
                waitForIdle()

                val file = (Updates.state.value as UpdateStates.State.Downloaded).file
                file.parentFile.canonicalFile shouldBe dirs.updates.canonicalFile
                file.readBytes().contentEquals(installer) shouldBe true
                server.requests.count { it.startsWith(releases.releasesPath + "/assets/") } shouldBe 2

                onAllNodes(hasText(localized(MR.strings.action_install)) and hasClickAction()).onFirst().performClick()
                Await.until("installer handover") { recorder.installed.isNotEmpty() }
                recorder.installed.toList() shouldBe listOf(file)
                recorder.other shouldBe emptyList()
            }
        } finally {
            Updates.includePrereleases().set(before)
        }
    }

    private class RecordingEffects : SystemEffects {
        val installed = ConcurrentLinkedQueue<File>()
        val other = ConcurrentLinkedQueue<String>()

        override fun browse(uri: URI) {
            other += "browse:$uri"
        }

        override fun openFolder(folder: File) {
            other += "folder:$folder"
        }

        override fun pickFile(request: FileRequest): File? = null.also { other += "pickFile" }

        override fun pickFolder(window: Window?, title: String, start: File): File? = null.also { other += "pickFolder" }

        override fun copy(contents: Transferable) {
            other += "copy"
        }

        override fun installUpdate(file: File, quit: () -> Unit) {
            installed += file
        }

        override fun exit(code: Int) {
            other += "exit:$code"
        }
    }
}

class UpdatesInstallTest {
    @Test
    fun `the installer tool is started by its full system path`() {
        val file = File("Zoukan-1.2.0.msi")
        Updates.installerCommand(Platform.WINDOWS, file, systemRoot = "C:\\Windows")!!.first() shouldBe
            File("C:\\Windows", "System32\\msiexec.exe").path
        Updates.installerCommand(Platform.MACOS, file)!!.first() shouldBe "/usr/bin/open"
        Updates.installerCommand(Platform.LINUX, file) shouldBe null
    }
}
