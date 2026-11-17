package zoukan.desktop.ui.settings

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.secrets.FileSecretStore
import zoukan.desktop.secrets.SecretStore
import zoukan.i18n.desktop.DMR
import java.io.File

@OptIn(ExperimentalTestApi::class, ForTestsOnly::class)
class DownloadPasswordDialogTest {

    @TempDir
    lateinit var temp: File

    private lateinit var security: SecurityPreferences
    private lateinit var password: CbzPassword
    private lateinit var store: RefusingStore

    /** The OS secret store, which can refuse a write (a denied Keychain prompt, a locked keyring). */
    private class RefusingStore(private val inner: SecretStore) : SecretStore by inner {
        var refuse = false

        /** Fail as the native store does when its library cannot load, instead of refusing. */
        var unloadable = false

        private fun fail() {
            if (unloadable) throw UnsatisfiedLinkError("no Security framework")
            check(!refuse) { "denied" }
        }

        override fun set(key: String, value: String) {
            fail()
            inner.set(key, value)
        }

        override fun delete(key: String) {
            fail()
            inner.delete(key)
        }
    }

    @BeforeEach
    fun setUp() {
        security = SecurityPreferences(DesktopPreferenceStore(JavaSharedPreferences("download_password_test_${System.nanoTime()}")))
        store = RefusingStore(FileSecretStore(File(temp, "secrets")))
        password = CbzPassword(store, security)
        password.set("old-pass")
        security.passwordProtectDownloads().set(true)
    }

    @AfterEach
    fun waitForWriters() {
        JavaSharedPreferences.awaitPendingSaves()
    }

    private var dismissed = false
    private var changed: Boolean? = null

    private fun ComposeUiTest.showDialog() = setContent {
        DownloadPasswordDialog(
            password,
            security.passwordProtectDownloads(),
            hasPassword = true,
            onPasswordChanged = { changed = it },
            onDismiss = { dismissed = true },
        )
    }

    /** The button with [label] of the dialog opened last: the confirmation, when one is open. */
    private fun ComposeUiTest.lastButton(label: String): SemanticsNodeInteraction = onAllNodesWithText(label).onLast()

    private fun ComposeUiTest.warningShown() =
        onNodeWithText(localized(DMR.strings.download_password_change_warning)).assertExists()

    @Test
    fun `removing the download password asks first and keeps it on cancel`() = runDesktopComposeUiTest {
        showDialog()
        onNodeWithText(localized(MR.strings.action_remove)).performClick()
        waitForIdle()

        password.get()?.concatToString() shouldBe "old-pass"
        security.passwordProtectDownloads().get() shouldBe true

        warningShown()
        lastButton(localized(MR.strings.action_cancel)).performClick()
        waitForIdle()
        password.get()?.concatToString() shouldBe "old-pass"
        security.passwordProtectDownloads().get() shouldBe true
    }

    @Test
    fun `removing the download password after confirming deletes it`() = runDesktopComposeUiTest {
        showDialog()
        onNodeWithText(localized(MR.strings.action_remove)).performClick()
        waitForIdle()
        lastButton(localized(MR.strings.action_remove)).performClick()
        waitForIdle()

        password.isSet() shouldBe false
        security.passwordProtectDownloads().get() shouldBe false
    }

    @Test
    fun `a new download password must be typed twice`() = runDesktopComposeUiTest {
        showDialog()
        onNodeWithTag("download-password").performTextInput("new-pass")
        onNodeWithText(localized(MR.strings.action_save)).assertIsNotEnabled()

        onNodeWithTag("download-password-repeat").performTextInput("new-pas")
        onNodeWithText(localized(MR.strings.action_save)).assertIsNotEnabled()
        onNodeWithText(localized(DMR.strings.passwords_do_not_match)).assertExists()

        onNodeWithTag("download-password-repeat").performTextInput("s")
        onNodeWithText(localized(MR.strings.action_save)).assertIsEnabled()

        // Changing a password that is set asks first; nothing is stored until it is confirmed
        onNodeWithText(localized(MR.strings.action_save)).performClick()
        waitForIdle()
        warningShown()
        password.get()?.concatToString() shouldBe "old-pass"

        lastButton(localized(MR.strings.action_save)).performClick()
        waitForIdle()
        password.get()?.concatToString() shouldBe "new-pass"
    }

    @Test
    fun `a secret store that refuses the new password keeps the old one and the dialog open`() = runDesktopComposeUiTest {
        showDialog()
        store.refuse = true
        onNodeWithTag("download-password").performTextInput("new-pass")
        onNodeWithTag("download-password-repeat").performTextInput("new-pass")
        onNodeWithText(localized(MR.strings.action_save)).performClick()
        waitForIdle()
        // Must not throw out of the click: on the window, that would close the app
        lastButton(localized(MR.strings.action_save)).performClick()
        waitForIdle()

        store.refuse = false
        password.get()?.concatToString() shouldBe "old-pass"
        dismissed shouldBe false
        changed shouldBe null
    }

    @Test
    fun `a secret store that refuses the removal keeps the password and the protection`() = runDesktopComposeUiTest {
        showDialog()
        store.refuse = true
        onNodeWithText(localized(MR.strings.action_remove)).performClick()
        waitForIdle()
        lastButton(localized(MR.strings.action_remove)).performClick()
        waitForIdle()

        store.refuse = false
        password.get()?.concatToString() shouldBe "old-pass"
        security.passwordProtectDownloads().get() shouldBe true
        dismissed shouldBe false
        changed shouldBe null
    }

    @Test
    fun `a secret store whose native library cannot load keeps the password and the dialog`() = runDesktopComposeUiTest {
        showDialog()
        store.unloadable = true
        onNodeWithText(localized(MR.strings.action_remove)).performClick()
        waitForIdle()
        lastButton(localized(MR.strings.action_remove)).performClick()
        waitForIdle()

        store.unloadable = false
        password.get()?.concatToString() shouldBe "old-pass"
        dismissed shouldBe false
    }
}
