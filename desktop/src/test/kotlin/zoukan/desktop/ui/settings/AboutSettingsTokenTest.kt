package zoukan.desktop.ui.settings

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import xyz.nulldev.androidcompat.io.sharedprefs.ForTestsOnly
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.Version
import zoukan.desktop.compat.localized
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
import java.io.File

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
