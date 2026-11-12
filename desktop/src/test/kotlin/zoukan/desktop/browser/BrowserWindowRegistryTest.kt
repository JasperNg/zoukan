package zoukan.desktop.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The registry the app's quit paths use to dispose the built-in browser windows (see
 * [BrowserWindow.disposeAllForQuit]), without Chromium on the classpath.
 */
class BrowserWindowRegistryTest {
    @Test
    fun `disposeAll disposes every window once and a second call does nothing`() {
        val registry = BrowserWindowRegistry()
        val disposed = mutableListOf<String>()
        registry.register { disposed += "a" }
        registry.register { disposed += "b" }

        registry.disposeAll()
        disposed shouldBe listOf("a", "b")

        // The quit paths run disposeAll more than once (at the quit, then before exitProcess)
        registry.disposeAll()
        disposed shouldBe listOf("a", "b")
    }

    @Test
    fun `a window that closed by itself is not disposed again`() {
        val registry = BrowserWindowRegistry()
        var disposed = 0
        val registration = registry.register { disposed++ }
        registry.unregister(registration)

        registry.disposeAll()
        disposed shouldBe 0
    }

    @Test
    fun `one window that cannot close does not stop the others`() {
        val registry = BrowserWindowRegistry()
        var disposed = false
        registry.register { error("wedged") }
        registry.register { disposed = true }

        registry.disposeAll()
        disposed shouldBe true
    }
}
