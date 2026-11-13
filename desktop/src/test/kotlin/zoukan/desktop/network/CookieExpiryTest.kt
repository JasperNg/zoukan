package zoukan.desktop.network

import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.webkit.CefCookieSync
import java.io.File
import java.net.HttpCookie
import java.net.URI

class CookieExpiryTest {
    @TempDir
    lateinit var root: File

    private fun cookie(name: String, value: String = "v", maxAge: Long = 3600) = HttpCookie(name, value).apply {
        this.maxAge = maxAge
        path = "/"
        version = 0
    }

    @Test
    fun `cookies refreshed on every response do not grow the expiry map`() {
        val store = PersistentCookieStore(File(root, "cookies.json"))
        repeat(5_000) { store.add(URI("https://example.org/"), cookie("__cf_bm", value = "v$it")) }
        store.get(URI("https://example.org/")).size shouldBe 1
        store.trackedExpiryCount() shouldBeLessThan 600
    }

    @Test
    fun `a removed cookie's expiry is dropped at the next save`() {
        val store = PersistentCookieStore(File(root, "cookies.json"))
        val c = cookie("session")
        store.add(URI("https://example.org/"), c)
        store.remove(URI("https://example.org/"), c)
        save(store)
        store.trackedExpiryCount() shouldBe 0
    }

    @Test
    fun `the store reports the same absolute expiry each time it is asked`() {
        val store = PersistentCookieStore(File(root, "cookies.json"))
        val c = cookie("cf_clearance")
        val before = System.currentTimeMillis()
        store.add(URI("https://example.org/"), c)
        val expires = store.expiresAt(c)!!
        (expires >= before + 3_600_000) shouldBe true
        Thread.sleep(20)
        store.expiresAt(c) shouldBe expires
        // What a copy to Chromium uses: the known expiry, not the max age from now
        CefCookieSync.expiresAt(c, expires, now = expires) shouldBe expires
    }

    @Test
    fun `without a known expiry the max age counts from now, and session cookies have none`() {
        CefCookieSync.expiresAt(cookie("a", maxAge = 10), known = null, now = 1_000) shouldBe 11_000
        CefCookieSync.expiresAt(cookie("b", maxAge = -1), known = 5L, now = 1_000) shouldBe null
    }

    @Test
    fun `saving replaces an existing cookie file and leaves no temp file`() {
        val file = File(root, "cookies.json")
        val store = PersistentCookieStore(file)
        store.add(URI("https://example.org/"), cookie("first"))
        save(store)
        store.add(URI("https://example.org/"), cookie("second"))
        save(store)

        File(file.path + ".tmp").exists() shouldBe false
        val names = PersistentCookieStore(file).get(URI("https://example.org/")).map { it.name }.toSet()
        names shouldBe setOf("first", "second")
        file.length() shouldNotBe 0L
    }

    private fun save(store: PersistentCookieStore) {
        PersistentCookieStore::class.java.getDeclaredMethod("save").apply { isAccessible = true }.invoke(store)
    }
}
