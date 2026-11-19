package zoukan.desktop.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.settings.ServerAddress.Verdict
import zoukan.i18n.desktop.DMR

@OptIn(ExperimentalTestApi::class, ForTestsOnly::class)
class SyncSettingsValidationTest {

    @AfterEach
    fun waitForWriters() {
        JavaSharedPreferences.awaitPendingSaves()
    }

    @Test
    fun `an address that is not http or https is refused`() {
        listOf("ftp://example.com/dav", "example.com", "https://", "http://exa mple.com").forEach {
            ServerAddress.check(it) shouldBe Verdict.Invalid
        }
    }

    @Test
    fun `plain http to a public host is warned`() {
        ServerAddress.check("http://example.com/webdav") shouldBe Verdict.PlainHttp
        ServerAddress.check("http://203.0.113.5:8080") shouldBe Verdict.PlainHttp
    }

    @Test
    fun `plain http to this computer or the local network is fine`() {
        listOf(
            "http://localhost:8282",
            "http://127.0.0.1",
            "http://[::1]:8080",
            "http://192.168.1.10/dav",
            "http://10.0.0.2",
            "http://172.20.1.1",
            "http://nas.local/dav",
            "https://example.com/dav",
        ).forEach { ServerAddress.check(it) shouldBe Verdict.Ok }
    }

    @Test
    fun `plain http over a private network such as Tailscale is fine`() {
        listOf(
            "http://100.101.102.103:8282",
            "http://100.64.0.1",
            "http://sync.internal/dav",
            "http://nas.home.arpa",
        ).forEach { ServerAddress.check(it) shouldBe Verdict.Ok }
        // Just outside 100.64.0.0/10
        ServerAddress.check("http://100.128.0.1") shouldBe Verdict.PlainHttp
    }

    @Test
    fun `an empty address clears the setting`() {
        ServerAddress.check("") shouldBe Verdict.Ok
        ServerAddress.check("   ") shouldBe Verdict.Ok
    }

    @Test
    fun `resetting the last sync time asks first`() = runDesktopComposeUiTest {
        var resets = 0
        setContent { ResetSyncTimeRow { resets++ } }

        onNodeWithText(localized(DMR.strings.reset_the_last_sync_time)).performClick()
        waitForIdle()
        onNodeWithText(localized(DMR.strings.reset_sync_time_warning)).assertExists()
        resets shouldBe 0

        onNodeWithText(localized(MR.strings.action_reset)).performClick()
        waitForIdle()
        resets shouldBe 1
    }

    @Test
    fun `replacing the server's library asks first, says what is lost, and cancel sends nothing`() = runDesktopComposeUiTest {
        var replaces = 0
        setContent { ReplaceServerLibraryRow(enabled = true) { replaces++ } }

        onNodeWithText(localized(DMR.strings.replace_server_library)).performClick()
        waitForIdle()
        onNodeWithText(localized(DMR.strings.replace_server_library_warning)).assertExists()
        onNodeWithText(localized(MR.strings.action_cancel)).performClick()
        waitForIdle()
        onNodeWithText(localized(DMR.strings.replace_server_library_warning)).assertDoesNotExist()
        replaces shouldBe 0

        onNodeWithText(localized(DMR.strings.replace_server_library)).performClick()
        waitForIdle()
        onNodeWithText(localized(DMR.strings.replace)).performClick()
        waitForIdle()
        replaces shouldBe 1
    }

    @Test
    fun `the server's library cannot be replaced while a sync runs`() = runDesktopComposeUiTest {
        var replaces = 0
        setContent { ReplaceServerLibraryRow(enabled = false) { replaces++ } }

        onNodeWithText(localized(DMR.strings.replace_server_library)).performClick()
        waitForIdle()
        onNodeWithText(localized(DMR.strings.replace_server_library_warning)).assertDoesNotExist()
        replaces shouldBe 0
    }

    @Test
    fun `a sync address that is refused cannot be saved`() = runDesktopComposeUiTest {
        val store = DesktopPreferenceStore(JavaSharedPreferences("sync_address_test_${System.nanoTime()}"))
        val preference = store.getString("address", "")
        setContent { TextDialog("Server URL", preference, secret = false, checkAddress = true, refusePlainHttp = true) {} }

        onNode(hasSetTextAction()).performTextInput("http://example.com/dav")
        onNodeWithText(localized(DMR.strings.plain_http_refused)).assertExists()
        onNodeWithText(localized(MR.strings.action_save)).assertIsNotEnabled()

        onNode(hasSetTextAction()).performTextReplacement("https://example.com/dav")
        onNodeWithText(localized(MR.strings.action_save)).assertIsEnabled()
        onNodeWithText(localized(MR.strings.action_save)).performClick()
        waitForIdle()
        preference.get() shouldBe "https://example.com/dav"
    }
}
