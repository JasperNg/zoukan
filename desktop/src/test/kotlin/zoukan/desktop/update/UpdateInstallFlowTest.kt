package zoukan.desktop.update

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.MR
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
import zoukan.desktop.e2e.FixtureReleases
import zoukan.desktop.e2e.FixtureServer
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testing.fakes.MemorySecretStore
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.ui.settings.aboutSettings
import zoukan.i18n.desktop.DMR
import java.awt.Window
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue

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
        Injekt.addSingleton<PreferenceStore>(InMemoryPreferenceStore())
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
