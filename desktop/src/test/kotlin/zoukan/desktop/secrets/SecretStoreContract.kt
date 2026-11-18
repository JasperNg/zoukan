package zoukan.desktop.secrets

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
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
