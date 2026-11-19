package zoukan.desktop.secrets

import app.cash.sqldelight.db.QueryResult
import com.sun.jna.Library
import com.sun.jna.Native
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.data.Database
import zoukan.desktop.Platform
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.testing.fakes.FailingSecretStore
import zoukan.desktop.testing.fakes.FailingSecretStore.Failure
import zoukan.desktop.testing.fakes.FailingSecretStore.Operation
import zoukan.desktop.testing.fakes.MemorySecretStore
import java.io.File
import java.util.HexFormat
import java.util.UUID

/**
 * What every [SecretStore] must do. Each store is a small subclass that says how to make it and
 * what its limits are.
 *
 * Every key a case uses comes from [key]: a test-only name with a random part, so a case can never
 * read, change or delete one of the app's own entries (`database-key`, the file key, the app lock,
 * the download password, the update token). The keys are deleted after each case, also when it fails.
 */
abstract class SecretStoreContract {

    /** The store under test; called once per case. */
    protected abstract fun createStore(): SecretStore

    /**
     * The largest value, in UTF-8 bytes, the store can keep; null when it has no limit that matters
     * (a value above [UNLIMITED_PROBE_BYTES] is then still expected to work). Above the limit, a
     * write must report an error, never keep a cut value.
     */
    protected open val maxValueBytes: Int? = null

    /**
     * Whether the store takes any key name. A store that does not (the file store: names are file
     * names) must refuse other names with an [IllegalArgumentException] and store nothing.
     */
    protected open val acceptsAnyKeyName: Boolean = true

    private val runId = UUID.randomUUID().toString().replace("-", "").take(12)
    private val usedKeys = mutableSetOf<String>()
    protected val store: SecretStore by lazy { createStore() }

    /** A test-only key for [name], recorded so it is deleted after the case. */
    protected fun key(name: String): String = "$KEY_PREFIX$runId-$name".also { usedKeys += it }

    @AfterEach
    fun removeTestEntries() {
        // Each on its own, so one failure does not leave the others behind
        usedKeys.forEach { key -> runCatching { store.delete(key) } }
        usedKeys.clear()
    }

    @Test
    fun `a value written reads back`() {
        val k = key("written")
        store.set(k, "value one")
        store.get(k) shouldBe "value one"
    }

    @Test
    fun `a second write replaces the value`() {
        val k = key("overwritten")
        store.set(k, "first")
        store.set(k, "second, longer than the first")
        store.get(k) shouldBe "second, longer than the first"
        store.set(k, "3")
        store.get(k) shouldBe "3"
    }

    @Test
    fun `a deleted key reads as missing`() {
        val k = key("deleted")
        store.set(k, "value")
        store.delete(k)
        store.get(k) shouldBe null
    }

    @Test
    fun `a key never written reads as missing`() {
        store.get(key("never-written")) shouldBe null
    }

    @Test
    fun `deleting a missing key is no error`() {
        val k = key("missing")
        store.delete(k)
        store.set(k, "value")
        store.delete(k)
        store.delete(k)
        store.get(k) shouldBe null
    }

    @Test
    fun `a value at the store's size limit reads back whole`() {
        val k = key("large")
        val size = maxValueBytes ?: UNLIMITED_PROBE_BYTES
        val value = valueOfBytes(size)
        store.set(k, value)
        store.get(k) shouldBe value
    }

    @Test
    fun `a value above the store's size limit is refused and the old value kept`() {
        val limit = maxValueBytes ?: return
        val k = key("too-large")
        store.set(k, "kept")
        shouldThrow<Exception> { store.set(k, valueOfBytes(limit + 1)) }
        // Never a cut value: the old one stays
        store.get(k) shouldBe "kept"
    }

    @Test
    fun `an empty value reads back as empty, not as missing`() {
        val k = key("empty")
        store.set(k, "")
        store.get(k) shouldBe ""
    }

    @Test
    fun `a non-ASCII value reads back unchanged`() {
        val k = key("non-ascii-value")
        val value = "Ünïcødé ключ 鍵 🔑  tab\tnewline\n"
        store.set(k, value)
        store.get(k) shouldBe value
    }

    @Test
    fun `a non-ASCII key works, or is refused without storing anything`() {
        val k = key("ключ-鍵-é")
        if (acceptsAnyKeyName) {
            store.set(k, "value")
            store.get(k) shouldBe "value"
            store.delete(k)
            store.get(k) shouldBe null
        } else {
            shouldThrow<IllegalArgumentException> { store.set(k, "value") }
            shouldThrow<IllegalArgumentException> { store.get(k) }
        }
    }

    @Test
    fun `two keys do not affect each other`() {
        val a = key("pair")
        val b = key("pair.second")
        store.set(a, "a")
        store.set(b, "b")
        store.set(a, "a again")
        store.get(b) shouldBe "b"
        store.delete(a)
        store.get(a) shouldBe null
        store.get(b) shouldBe "b"
    }

    companion object {
        /** Every key the contract writes starts with this; no key of the app does. */
        const val KEY_PREFIX = "zoukan-contract-test-"

        /** The large value tried on a store with no limit. */
        const val UNLIMITED_PROBE_BYTES = 64 * 1024

        /** An ASCII value of exactly [bytes] bytes in UTF-8, not all the same character. */
        fun valueOfBytes(bytes: Int): String = buildString(bytes) { repeat(bytes) { append('a' + it % 26) } }
    }
}

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

/**
 * Each failure an OS secret store reports (locked, denied, unavailable, a garbled value), through
 * the code that reads the keys at start: the file key for the preference and cookie files
 * ([FileCipher.fromStore], first) and the database key ([DesktopDatabase.createDriver], after).
 *
 * For each: the start stops with an error the crash dialog can show (no hang, no half-built
 * runtime), and an existing database is never opened, rewritten or given a new key, which would
 * look like data loss. A first run with a failing store makes no database at all.
 */
class SecretStoreFailureTest {
    @TempDir
    lateinit var dir: File

    private val database get() = File(dir, "zoukan.db")

    /** A database made by this build, its key in the returned store, which has recorded no writes yet. */
    private fun existingDatabase(): FailingSecretStore {
        val store = FailingSecretStore()
        DesktopDatabase.createDriver(database, store).close()
        (database.length() > 0) shouldBe true
        store.attemptedWrites.clear()
        return store
    }

    /** Every file in the folder (the database, its WAL and shared-memory files), by name. */
    private fun files(): Map<String, List<Byte>> =
        dir.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes().toList() }

    private fun FailingSecretStore.databaseKey() = backing.values[DesktopDatabase.KEY_NAME]

    private fun dialogFor(e: Throwable) = CrashReporter.crashDialogMessage(e, report = null, incognito = false)

    // --- The database key ---

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `a store that cannot be read leaves an existing database as it was`(failure: Failure) {
        val store = existingDatabase()
        val key = store.databaseKey()
        val before = files()
        store.failOn(Operation.READ, failure)

        val e = shouldThrow<IllegalStateException> { DesktopDatabase.createDriver(database, store) }

        files() shouldBe before
        store.attemptedWrites.shouldBeEmpty()
        store.databaseKey() shouldBe key
        dialogFor(e).shouldNotBeBlank()
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `on a first run a store that cannot be read makes no key and no database`(failure: Failure) {
        val store = FailingSecretStore().failOn(Operation.READ, failure)

        shouldThrow<IllegalStateException> { DesktopDatabase.createDriver(database, store) }

        store.attemptedWrites.shouldBeEmpty()
        files() shouldBe emptyMap()
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `on a first run a store that cannot be written makes no database`(failure: Failure) {
        val store = FailingSecretStore().failOn(Operation.WRITE, failure)

        val e = shouldThrow<IllegalStateException> { DesktopDatabase.createDriver(database, store) }

        // A database whose key was never kept could not be opened again
        files() shouldBe emptyMap()
        store.backing.values shouldBe emptyMap()
        dialogFor(e) shouldContain failure.exception(Operation.WRITE).message!!
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `a store that reads but can no longer write still opens the existing database, writing no key`(failure: Failure) {
        val store = existingDatabase()
        val key = store.databaseKey()
        store.failOn(Operation.WRITE, failure).failOn(Operation.DELETE, failure)

        DesktopDatabase.createDriver(database, store).use { driver ->
            driver.executeQuery(null, "PRAGMA user_version", { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else 0L) }, 0)
                .value shouldBe Database.Schema.version
        }

        store.attemptedWrites.shouldBeEmpty()
        store.databaseKey() shouldBe key
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `a store that fails midway at start, after the file key was read, leaves the database as it was`(failure: Failure) {
        val store = existingDatabase()
        FileCipher.fromStore(store)
        store.attemptedWrites.clear()
        val keys = store.backing.values.toMap()
        val before = files()
        // The start reads the file key first, then the database key
        store.failOn(Operation.READ, failure, succeedFirst = 1)

        FileCipher.fromStore(store)
        val e = shouldThrow<IllegalStateException> { DesktopDatabase.createDriver(database, store) }

        files() shouldBe before
        store.attemptedWrites.shouldBeEmpty()
        store.backing.values shouldBe keys
        dialogFor(e) shouldContain failure.exception(Operation.READ).message!!
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "not a hex key",
            "",
            "   ",
            // Hex, but 2 bytes, not 32
            "abcd",
            // 31 bytes
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e",
            // A whole key, but not this database's
            "ff0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
        ],
    )
    fun `a garbled database key never opens or rewrites the existing database, and is not replaced`(garbled: String) {
        val store = existingDatabase()
        store.backing.values[DesktopDatabase.KEY_NAME] = garbled
        val before = files()

        shouldThrow<Exception> { DesktopDatabase.createDriver(database, store) }

        files() shouldBe before
        store.attemptedWrites.shouldBeEmpty()
        store.databaseKey() shouldBe garbled
    }

    @ParameterizedTest
    @ValueSource(strings = ["not a hex key", "   ", "zz"])
    fun `on a first run a database key that is not hex makes no database and is not replaced`(garbled: String) {
        val store = FailingSecretStore()
        store.backing.values[DesktopDatabase.KEY_NAME] = garbled

        shouldThrow<IllegalArgumentException> { DesktopDatabase.createDriver(database, store) }

        files() shouldBe emptyMap()
        store.attemptedWrites.shouldBeEmpty()
        store.databaseKey() shouldBe garbled
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // An empty value (a Credential Manager entry with no blob reads as "")
            "",
            // Hex, but 2 bytes
            "abcd",
            // 31 bytes
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e",
        ],
    )
    fun `on a first run a stored database key of the wrong length makes no database`(garbled: String) {
        val store = FailingSecretStore()
        store.backing.values[DesktopDatabase.KEY_NAME] = garbled

        shouldThrow<Exception> { DesktopDatabase.createDriver(database, store) }

        files() shouldBe emptyMap()
        store.attemptedWrites.shouldBeEmpty()
    }

    @Test
    fun `an existing database whose key is gone is refused with a message the dialog shows`() {
        val store = existingDatabase()
        store.backing.values.remove(DesktopDatabase.KEY_NAME)
        val before = files()

        val e = shouldThrow<DesktopDatabase.KeyMissingException> { DesktopDatabase.createDriver(database, store) }

        files() shouldBe before
        store.attemptedWrites.shouldBeEmpty()
        dialogFor(e) shouldContain e.message!!
    }

    // --- The file key (preference and cookie files) ---

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `a file key that cannot be read stops the start with the store's own message and writes nothing`(failure: Failure) {
        val store = FailingSecretStore()
        FileCipher.fromStore(store)
        store.attemptedWrites.clear()
        val keys = store.backing.values.toMap()
        store.failOn(Operation.READ, failure)

        val e = shouldThrow<SecretStoreUnavailableException> { FileCipher.fromStore(store) }

        e.storeName shouldBe store.name
        store.attemptedWrites.shouldBeEmpty()
        store.backing.values shouldBe keys
        // The dialog shows the app's own text, which names the store
        val dialog = dialogFor(e)
        dialog shouldContain e.message!!
        dialog shouldContain store.name
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `on a first run a file key that cannot be written stops the start and keeps no key`(failure: Failure) {
        val store = FailingSecretStore().failOn(Operation.WRITE, failure)

        val e = shouldThrow<IllegalStateException> { FileCipher.fromStore(store) }

        store.backing.values shouldBe emptyMap()
        dialogFor(e).shouldNotBeBlank()
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `a garbled file key is not replaced when keeping it aside fails`(failure: Failure) {
        val store = FailingSecretStore()
        store.backing.values[FileCipher.KEY_NAME] = "garbled"
        store.failOn(Operation.WRITE, failure)

        shouldThrow<IllegalStateException> { FileCipher.fromStore(store) }

        store.backing.values shouldBe mapOf(FileCipher.KEY_NAME to "garbled")
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `a garbled file key kept aside is not replaced when the new key cannot be written`(failure: Failure) {
        val store = FailingSecretStore()
        store.backing.values[FileCipher.KEY_NAME] = "garbled"
        store.failOn(Operation.WRITE, failure, succeedFirst = 1)

        shouldThrow<IllegalStateException> { FileCipher.fromStore(store) }

        store.backing.values shouldBe mapOf(FileCipher.KEY_NAME to "garbled", FileCipher.UNREADABLE_KEY_NAME to "garbled")
    }

    @Test
    fun `a file key read back with surrounding white space is the same key, and nothing is written`() {
        val store = FailingSecretStore()
        val key = ByteArray(32) { it.toByte() }
        store.backing.values[FileCipher.KEY_NAME] = " ${HexFormat.of().formatHex(key)}\n"
        store.failOn(Operation.WRITE, Failure.DENIED)

        val cipher = FileCipher.fromStore(store)

        FileCipher(key).decode(cipher.encode("x".toByteArray())).decodeToString() shouldBe "x"
        store.attemptedWrites.shouldBeEmpty()
    }
}
