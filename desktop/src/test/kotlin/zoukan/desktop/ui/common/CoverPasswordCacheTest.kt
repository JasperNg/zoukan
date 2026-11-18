package zoukan.desktop.ui.common

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.testing.fakes.FailingSecretStore
import zoukan.desktop.testing.fakes.MemorySecretStore
import java.util.concurrent.atomic.AtomicInteger

/**
 * The download password for encrypted covers is read from the OS secret store once a session, not
 * for every `cover.cbi` in a grid: a denied Keychain prompt would come back once per cover.
 */
class CoverPasswordCacheTest {

    private class Counting(private val store: SecretStore) : SecretStore by store {
        val reads = AtomicInteger()

        override fun get(key: String): String? {
            reads.incrementAndGet()
            return store.get(key)
        }
    }

    private val preferences = mockk<SecurityPreferences>(relaxed = true)

    @Test
    fun `the password is read once for many covers`() {
        val store = Counting(MemorySecretStore())
        val password = CbzPassword(store, preferences).apply { set("secret") }
        val cache = CoverPasswordCache { password }

        repeat(20) { String(cache.get()!!) shouldBe "secret" }
        store.reads.get() shouldBe 1
    }

    @Test
    fun `a store that fails is asked once, not once per cover`() {
        val failing = FailingSecretStore().failAll(FailingSecretStore.Failure.DENIED)
        val store = Counting(failing)
        val cache = CoverPasswordCache { CbzPassword(store, preferences) }

        repeat(20) { cache.get() shouldBe null }
        store.reads.get() shouldBe 1
    }

    @Test
    fun `changing the password in settings reads it again`() {
        val store = Counting(MemorySecretStore())
        val password = CbzPassword(store, preferences).apply { set("old") }
        val cache = CoverPasswordCache { password }
        String(cache.get()!!) shouldBe "old"

        password.set("new")
        String(cache.get()!!) shouldBe "new"
        password.set("")
        cache.get() shouldBe null
        store.reads.get() shouldBe 3
    }

    @Test
    fun `a caller wiping its copy does not wipe the kept one`() {
        val password = CbzPassword(MemorySecretStore(), preferences).apply { set("secret") }
        val cache = CoverPasswordCache { password }
        cache.get()!!.fill('\u0000')
        String(cache.get()!!) shouldBe "secret"
    }
}
