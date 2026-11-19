package zoukan.desktop.ui.common

import coil3.memory.MemoryCache
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.testing.fakes.MemorySecretStore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Locking the app forgets what the lock should hide: the download password kept for encrypted
 * covers and the decrypted images in Coil's memory cache. After the unlock they are read again.
 */
class AppLockForgetsDecryptedTest {

    private class Counting(private val store: SecretStore) : SecretStore by store {
        val reads = AtomicInteger()

        override fun get(key: String): String? {
            reads.incrementAndGet()
            return store.get(key)
        }
    }

    @Test
    fun `clearing the cover password cache reads the password again`() {
        val store = Counting(MemorySecretStore())
        val password = CbzPassword(store, mockk<SecurityPreferences>(relaxed = true)).apply { set("secret") }
        val cache = CoverPasswordCache { password }
        String(cache.get()!!) shouldBe "secret"
        store.reads.get() shouldBe 1

        cache.clear()

        String(cache.get()!!) shouldBe "secret"
        store.reads.get() shouldBe 2
    }

    @Test
    fun `forgetting empties the image memory cache and the password cache`() {
        val memory = MemoryCache.Builder().maxSizeBytes(1024 * 1024).build()
        memory[MemoryCache.Key("-1:file:/a/cover.cbi:0")] = MemoryCache.Value(FakeImage)
        val store = Counting(MemorySecretStore())
        val password = CbzPassword(store, mockk<SecurityPreferences>(relaxed = true)).apply { set("secret") }
        val cache = CoverPasswordCache { password }
        cache.get()

        Images.forgetDecrypted(memory, cache)

        memory.size shouldBe 0L
        cache.get()
        store.reads.get() shouldBe 2
    }

    @Test
    fun `each lock forgets once, an unlock or the unlocked start does not`() = runTest {
        val locked = MutableStateFlow(false)
        val forgets = AtomicInteger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        Images.followAppLock(locked, scope) { forgets.incrementAndGet() }
        forgets.get() shouldBe 0

        locked.value = true
        forgets.get() shouldBe 1
        locked.value = false
        forgets.get() shouldBe 1
        locked.value = true
        forgets.get() shouldBe 2
        scope.cancel()
    }

    @Test
    fun `a forget that fails does not stop the next lock from forgetting`() = runTest {
        val locked = MutableStateFlow(false)
        val forgets = AtomicInteger()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        Images.followAppLock(locked, scope) {
            if (forgets.incrementAndGet() == 1) throw IllegalStateException("cache gone")
        }
        locked.value = true
        locked.value = false
        locked.value = true
        forgets.get() shouldBe 2
        scope.cancel()
    }

    private object FakeImage : coil3.Image {
        override val size = 100L
        override val width = 10
        override val height = 10
        override val shareable = true
        override fun draw(canvas: org.jetbrains.skia.Canvas) = Unit
    }
}
