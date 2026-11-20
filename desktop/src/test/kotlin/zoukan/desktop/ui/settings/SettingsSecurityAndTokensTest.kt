package zoukan.desktop.ui.settings

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_cancel
import tachiyomi.i18n.action_remove
import tachiyomi.i18n.action_save
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.Version
import zoukan.desktop.compat.localized
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.secrets.FileSecretStore
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.testing.Await
import zoukan.desktop.testing.FreshInjekt
import zoukan.desktop.testing.fakes.FailingSecretStore
import zoukan.desktop.testing.fakes.MemorySecretStore
import zoukan.desktop.ui.common.LazyColumn
import zoukan.desktop.update.UpdateChecker
import zoukan.desktop.update.Updates
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.could_not_use_the_secret_store
import zoukan.i18n.desktop.download_password_change_warning
import zoukan.i18n.desktop.github_token
import zoukan.i18n.desktop.github_token_set
import zoukan.i18n.desktop.needed_while_the_release_repository_is
import zoukan.i18n.desktop.passwords_do_not_match
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

/**
 * The About page's GitHub token row tells a stored token, no token and a secret store that could
 * not be read apart: a locked Keychain or a denied prompt must not read as "no token", which would
 * send the user to add a token they already have. Saving writes only to [SecretStore], never to
 * [PreferenceStore] or any other file in the data folder, and Remove deletes it.
 */
@OptIn(ExperimentalTestApi::class, ForTestsOnly::class)
class AboutSettingsTokenTest {
    @JvmField
    @RegisterExtension
    val injekt = FreshInjekt()

    @TempDir
    lateinit var dataDir: File

    private fun checker(store: SecretStore) =
        UpdateChecker(OkHttpClient(), store, repo = "o/r", current = Version(listOf(0, 1)), platform = Platform.WINDOWS)

    @Test
    fun `a stored token reads as set and none as not set`() {
        val store = MemorySecretStore()
        tokenState(checker(store)) shouldBe TokenState.NOT_SET
        checker(store).token = "secret"
        tokenState(checker(store)) shouldBe TokenState.SET
    }

    @Test
    fun `a secret store that cannot be read says so instead of no token`() {
        for (failure in FailingSecretStore.Failure.entries) {
            val store = FailingSecretStore().also { it.backing.set(UpdateChecker.TOKEN_KEY, "secret") }
            store.failOn(FailingSecretStore.Operation.READ, failure)

            val state = tokenState(checker(store))

            state shouldBe TokenState.UNREADABLE
            tokenSubtitle(state, store.name) shouldBe localized(DMR.strings.could_not_use_the_secret_store)
        }
    }

    @Test
    fun `the row says what the token is for while the store can be read`() {
        tokenSubtitle(TokenState.NOT_SET, "Keychain") shouldBe localized(DMR.strings.needed_while_the_release_repository_is, "Keychain")
        tokenSubtitle(TokenState.SET, "Keychain") shouldBe localized(DMR.strings.needed_while_the_release_repository_is, "Keychain")
    }

    @Test
    fun `saving a token writes only to the secret store and Remove deletes it`() {
        val property = "$CONFIG_PREFIX.server.rootDir"
        val previous = System.getProperty(property)
        System.setProperty(property, dataDir.absolutePath)
        val secrets = FileSecretStore(File(dataDir, "secrets"))
        val preferences = DesktopPreferenceStore(JavaSharedPreferences("about_token_test_${System.nanoTime()}"))
        Injekt.addSingleton<SecretStore>(secrets)
        Injekt.addSingleton<PreferenceStore>(preferences)
        Injekt.addSingleton(ZoukanDirs(dataDir))
        Updates.checkerForTests = UpdateChecker(OkHttpClient(), secrets)
        try {
            runDesktopComposeUiTest {
                setContent { LazyColumn(Modifier.testTag("about-list")) { aboutSettings() } }
                val list = onNodeWithTag("about-list")
                list.performScrollToNode(hasText(localized(DMR.strings.github_token)))
                onNodeWithText(localized(DMR.strings.github_token)).performClick()
                waitForIdle()

                onNode(hasSetTextAction()).performTextInput(TEST_TOKEN)
                onNodeWithText(localized(MR.strings.action_save)).performClick()
                Await.until("the token in SecretStore") { secrets.get(UpdateChecker.TOKEN_KEY) == TEST_TOKEN }
                waitForIdle()

                onNodeWithText(localized(DMR.strings.github_token_set)).assertExists()
                JavaSharedPreferences.awaitPendingSaves()
                preferences.getAll().values.none { TEST_TOKEN in it.toString() } shouldBe true
                val leaked = dataDir.walkTopDown()
                    .filter { it.isFile && it.name != UpdateChecker.TOKEN_KEY }
                    .filter { file -> runCatching { TEST_TOKEN.toByteArray() in file.readBytes() }.getOrDefault(false) }
                    .toList()
                leaked shouldBe emptyList()

                onNodeWithText(localized(DMR.strings.github_token_set)).performClick()
                waitForIdle()
                onNodeWithText(localized(MR.strings.action_remove)).performClick()
                Await.until("the token to be removed from SecretStore") { secrets.get(UpdateChecker.TOKEN_KEY) == null }
                waitForIdle()
                onNodeWithText(localized(DMR.strings.github_token)).assertExists()
            }
        } finally {
            JavaSharedPreferences.awaitPendingSaves()
            Updates.checkerForTests = null
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    private companion object {
        const val TEST_TOKEN = "ghp_isolatedSettingsFixtureToken012345"
    }
}

private operator fun ByteArray.contains(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
