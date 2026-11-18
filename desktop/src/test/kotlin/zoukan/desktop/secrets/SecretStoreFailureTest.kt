package zoukan.desktop.secrets

import app.cash.sqldelight.db.QueryResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.data.Database
import zoukan.desktop.app.CrashReporter
import zoukan.desktop.db.DesktopDatabase
import zoukan.desktop.testing.fakes.FailingSecretStore
import zoukan.desktop.testing.fakes.FailingSecretStore.Failure
import zoukan.desktop.testing.fakes.FailingSecretStore.Operation
import java.io.File
import java.util.HexFormat

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
