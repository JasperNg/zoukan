package zoukan.desktop.ui.settings

import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import zoukan.desktop.Platform
import zoukan.desktop.app.Version
import zoukan.desktop.compat.localized
import zoukan.desktop.testing.fakes.FailingSecretStore
import zoukan.desktop.testing.fakes.MemorySecretStore
import zoukan.desktop.update.UpdateChecker
import zoukan.i18n.desktop.DMR

/**
 * The About page's GitHub token row tells a stored token, no token and a secret store that could
 * not be read apart: a locked Keychain or a denied prompt must not read as "no token", which would
 * send the user to add a token they already have.
 */
class AboutSettingsTokenTest {
    private fun checker(store: zoukan.desktop.secrets.SecretStore) =
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
}
