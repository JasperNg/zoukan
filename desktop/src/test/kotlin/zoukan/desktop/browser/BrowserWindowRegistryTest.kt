package zoukan.desktop.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The registry the app's quit paths use to dispose the built-in browser windows (see
 * [BrowserWindow.disposeAllForQuit]), without Chromium on the classpath.
 */
class BrowserWindowRegistryTest {
    @Test
    fun `a pop-up to the same site loads in the window's own browser`() {
        BrowserPopups.decide("https://accounts.example.com/signin", "https://www.example.com/page", offerCrossSite = true) shouldBe
            BrowserPopups.Action.LoadHere("https://accounts.example.com/signin")
        // Also in a check's window, which drops only other sites' pop-ups
        BrowserPopups.decide("https://example.com/next", "https://example.com/", offerCrossSite = false) shouldBe
            BrowserPopups.Action.LoadHere("https://example.com/next")
    }

    @Test
    fun `a pop-up to another site is stopped and offered to the user`() {
        BrowserPopups.decide("https://other.example.org/x", "https://www.example.com/", offerCrossSite = true) shouldBe
            BrowserPopups.Action.Offer("https://other.example.org/x", "other.example.org")
        // Unknown opener (the blank page before the first load): not the same site
        BrowserPopups.decide("https://other.example.org/x", null, offerCrossSite = true) shouldBe
            BrowserPopups.Action.Offer("https://other.example.org/x", "other.example.org")
    }

    @Test
    fun `a pop-up to another site is dropped silently in a check's window or when it goes to a blocked host`() {
        BrowserPopups.decide("https://other.example.org/", "https://example.com/", offerCrossSite = false) shouldBe BrowserPopups.Action.Drop
        BrowserPopups.decide("https://ads.example.net/", "https://example.com/", offerCrossSite = true, isBlockedHost = { true }) shouldBe
            BrowserPopups.Action.Drop
    }

    @Test
    fun `a failing block check offers the pop-up instead of failing`() {
        BrowserPopups.decide("https://ads.example.net/", "https://example.com/", offerCrossSite = true, isBlockedHost = { error("boom") }) shouldBe
            BrowserPopups.Action.Offer("https://ads.example.net/", "ads.example.net")
    }

    @Test
    fun `a pop-up to a non-web address is dropped`() {
        listOf("file:///C:/Windows/win.ini", "javascript:alert(1)", "about:blank", "", null).forEach { url ->
            BrowserPopups.decide(url, "https://example.com/", offerCrossSite = true) shouldBe BrowserPopups.Action.Drop
        }
    }

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
    fun `a window that opens while the others are disposed is kept for the next call`() {
        val registry = BrowserWindowRegistry()
        val disposed = mutableListOf<String>()
        registry.register {
            disposed += "a"
            registry.register { disposed += "late" }
        }

        registry.disposeAll()
        disposed shouldBe listOf("a")

        registry.disposeAll()
        disposed shouldBe listOf("a", "late")
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

    /** A browser window as the registry sees it: hidden while the app is locked, as BrowserWindow. */
    private class FakeWindow(private val registry: BrowserWindowRegistry) {
        var visible = !registry.locked
        var changes = 0
        val registration = registry.register(onLockChange = {
            changes++
            visible = !registry.locked
        }) {}
    }

    @Test
    fun `the windows hide while the app is locked and come back at the unlock`() {
        val registry = BrowserWindowRegistry()
        val a = FakeWindow(registry)
        registry.register(onLockChange = { error("wedged") }) {}
        val b = FakeWindow(registry)
        a.visible shouldBe true

        registry.setLocked(true)
        a.visible shouldBe false
        b.visible shouldBe false
        // A window opening while locked opens hidden
        FakeWindow(registry).visible shouldBe false

        registry.setLocked(false)
        a.visible shouldBe true
        b.visible shouldBe true
    }

    @Test
    fun `the same lock state twice tells nobody, and a closed window is not told`() {
        val registry = BrowserWindowRegistry()
        val open = FakeWindow(registry)
        val closed = FakeWindow(registry)
        registry.unregister(closed.registration)

        registry.setLocked(false)
        open.changes shouldBe 0
        registry.setLocked(true)
        registry.setLocked(true)
        open.changes shouldBe 1
        closed.changes shouldBe 0
    }

    @Test
    fun `a challenge waits for the unlock`() {
        val registry = BrowserWindowRegistry()
        registry.awaitUnlocked(10) shouldBe true

        registry.setLocked(true)
        registry.awaitUnlocked(50) shouldBe false

        val unlocker = Thread {
            Thread.sleep(100)
            registry.setLocked(false)
        }.apply { start() }
        val started = System.nanoTime()
        registry.awaitUnlocked(10_000) shouldBe true
        // Woken by the unlock, not the timeout
        (System.nanoTime() - started < 5_000_000_000L) shouldBe true
        unlocker.join()
    }

    @Test
    fun `a challenge solved some other way while locked stops waiting`() {
        val registry = BrowserWindowRegistry()
        registry.setLocked(true)
        val started = System.nanoTime()
        registry.awaitUnlocked(10_000) { true } shouldBe false
        (System.nanoTime() - started < 5_000_000_000L) shouldBe true
    }
}
