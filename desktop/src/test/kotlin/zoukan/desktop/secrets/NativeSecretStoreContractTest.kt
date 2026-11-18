package zoukan.desktop.secrets

import com.sun.jna.Library
import com.sun.jna.Native
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import zoukan.desktop.Platform

/**
 * The contract against the real OS stores, under test-only keys ([SecretStoreContract.key]: a
 * fixed prefix and a random part) that each case deletes afterwards.
 *
 * The production stores keep every entry under the service name [SecretStore.SERVICE] and have no
 * way to take another one, so the test entries share the app's service name but never its key
 * names: on macOS the account is the test key, on Windows the target is `Zoukan/<test key>`.
 *
 * They run in `:desktop:test` on their own OS and skip elsewhere, when the environment variable
 * [SKIP_SWITCH] is `true`, and when the store cannot be used here: a locked login keychain (no
 * prompt is ever shown: user interaction is turned off while they run), or a Windows logon with
 * no Credential Manager, such as an SSH session (error 1312). The Keychain tests run only when
 * [KEYCHAIN_SWITCH] is `true` (see [MacKeychainStoreContractTest]).
 */
private const val SKIP_SWITCH = "ZOUKAN_SKIP_NATIVE_SECRET_TESTS"

/** Opts in to [MacKeychainStoreContractTest], which changes a Keychain Services setting that is not documented as per-process. */
private const val KEYCHAIN_SWITCH = "ZOUKAN_KEYCHAIN_TESTS"

private fun keychainTestsWanted() = System.getenv(KEYCHAIN_SWITCH) == "true"

private fun assumeNativeStoresWanted() {
    assumeTrue(System.getenv(SKIP_SWITCH) != "true", "$SKIP_SWITCH=true: the native secret store tests are turned off")
}

/** Probes [store] with a read of a key that does not exist; skips the case when [unavailable] says the error means the store can't be used here. */
private fun assumeUsable(store: SecretStore, probeKey: String, unavailable: (String) -> String?) {
    val error = runCatching { store.get(probeKey) }.exceptionOrNull() ?: return
    val reason = unavailable(error.message.orEmpty())
    assumeTrue(reason == null, "${store.name} cannot be used here: $reason (${error.message})")
    throw error
}

/**
 * The contract against the macOS Keychain. Opt-in with [KEYCHAIN_SWITCH]`=true`.
 *
 * To never wait on a prompt, the tests turn Keychain user interaction off
 * (`SecKeychainSetUserInteractionAllowed(false)`) and put it back afterwards. That setting is not
 * documented as the calling process's own: the SDK header (`SecKeychain.h`) only says it "turns on
 * or off any optional user interaction", and Apple's page for the function says that failing to
 * turn it back on "will affect other clients of the Keychain Services". So a test run could, while
 * it lasts (or after a crash before [restorePrompts]), keep another program's Keychain prompt from
 * showing. The tests run only when asked: on a throwaway CI runner, or by hand on a Mac where that
 * does not matter.
 */
class MacKeychainStoreContractTest : SecretStoreContract() {
    override fun createStore(): SecretStore = MacKeychainStore()

    @BeforeEach
    fun requireKeychain() {
        assumeTrue(Platform.current == Platform.MACOS, "the macOS Keychain is only on macOS")
        assumeNativeStoresWanted()
        assumeTrue(keychainTestsWanted(), "$KEYCHAIN_SWITCH is not true: the Keychain tests are opt-in")
        assumeUsable(store, key("probe")) { message ->
            UNAVAILABLE_STATUSES.entries.firstOrNull { (status, _) -> message.endsWith("status $status") }?.value
        }
    }

    /** No Keychain prompt during the tests: a call that would need one fails with -25308 instead. */
    private interface SecurityUi : Library {
        @Suppress("FunctionName")
        fun SecKeychainSetUserInteractionAllowed(state: Byte): Int

        @Suppress("FunctionName")
        fun SecKeychainGetUserInteractionAllowed(state: ByteArray): Int
    }

    companion object {
        private val UNAVAILABLE_STATUSES = mapOf(
            -25308 to "the login keychain is locked and a prompt would be needed",
            -25307 to "there is no default keychain",
            -25291 to "no keychain is available",
            -25294 to "the keychain could not be found",
        )

        private var interactionBefore: Byte? = null

        @JvmStatic
        @BeforeAll
        fun noPrompts() {
            if (Platform.current != Platform.MACOS || !keychainTestsWanted() || System.getenv(SKIP_SWITCH) == "true") return
            val ui = Native.load("Security", SecurityUi::class.java)
            val state = ByteArray(1)
            if (ui.SecKeychainGetUserInteractionAllowed(state) == 0) interactionBefore = state[0]
            ui.SecKeychainSetUserInteractionAllowed(0)
        }

        @JvmStatic
        @AfterAll
        fun restorePrompts() {
            val before = interactionBefore ?: return
            Native.load("Security", SecurityUi::class.java).SecKeychainSetUserInteractionAllowed(before)
        }
    }
}

class WindowsCredentialStoreContractTest : SecretStoreContract() {
    override fun createStore(): SecretStore = WindowsCredentialStore()

    // CRED_MAX_CREDENTIAL_BLOB_SIZE: 5 * 512 bytes for a generic credential
    override val maxValueBytes = 2560

    @BeforeEach
    fun requireCredentialManager() {
        assumeTrue(Platform.current == Platform.WINDOWS, "Windows Credential Manager is only on Windows")
        assumeNativeStoresWanted()
        assumeUsable(store, key("probe")) { message ->
            if (message.endsWith("error 1312")) "this logon session has no Credential Manager (an SSH logon); run it in a desktop session" else null
        }
    }
}
