package zoukan.desktop.secrets

import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.testing.fakes.FailingSecretStore
import zoukan.desktop.testing.fakes.MemorySecretStore
import java.io.File

/** The file store (Linux, throwaway profiles, the journeys) keeps the contract. */
class FileSecretStoreContractTest : SecretStoreContract() {
    @TempDir
    lateinit var dir: File

    override fun createStore(): SecretStore = FileSecretStore(File(dir, "secrets"))

    // Key names are file names: letters, digits, '_', '.', '-'
    override val acceptsAnyKeyName = false
}

/** The in-memory fake the failure tests build on keeps the contract, so it does not mislead them. */
class MemorySecretStoreContractTest : SecretStoreContract() {
    override fun createStore(): SecretStore = MemorySecretStore()
}

/** The failing fake, with no failure planned, behaves as the store it wraps. */
class FailingSecretStoreContractTest : SecretStoreContract() {
    override fun createStore(): SecretStore = FailingSecretStore()
}
