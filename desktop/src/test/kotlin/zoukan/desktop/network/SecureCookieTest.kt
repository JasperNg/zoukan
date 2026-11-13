package zoukan.desktop.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.webkit.cookiesByUri
import java.io.File
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI

/**
 * The JDK cookie store keeps its URIs as http://host and a lookup by an http URI leaves Secure
 * cookies out, so they were neither copied to Chromium nor saved to disk.
 */
class SecureCookieTest {
    @TempDir
    lateinit var root: File

    private fun cookie(
        name: String,
        secure: Boolean,
    ) = HttpCookie(name, "$name-value").apply {
        maxAge = 3600
        path = "/"
        this.secure = secure
    }

    @Test
    fun `cookiesByUri includes Secure cookies`() {
        val store = CookieManager().cookieStore
        store.add(URI("https://example.org/"), cookie("plain", secure = false))
        store.add(URI("https://example.org/"), cookie("cf_clearance", secure = true))

        // What enumerating by the stored URIs alone gives
        store.urIs.flatMap { store.get(it) }.map { it.name } shouldBe listOf("plain")

        val found = store.cookiesByUri()
        found.map { it.second.name }.toSet() shouldBe setOf("plain", "cf_clearance")
        found.map { it.first.host }.toSet() shouldBe setOf("example.org")
    }

    @Test
    fun `a Secure cookie is saved and read back`() {
        val file = File(root, "cookies.json")
        val store = PersistentCookieStore(file)
        store.add(URI("https://example.org/"), cookie("cf_clearance", secure = true))
        save(store)

        val reloaded = PersistentCookieStore(file)
        val cookie = reloaded.get(URI("https://example.org/")).single()
        cookie.name shouldBe "cf_clearance"
        cookie.value shouldBe "cf_clearance-value"
        cookie.secure shouldBe true
        // Still not sent over plain http
        reloaded.get(URI("http://example.org/")) shouldBe emptyList()
    }

    private fun save(store: PersistentCookieStore) {
        PersistentCookieStore::class.java.getDeclaredMethod("save").apply { isAccessible = true }.invoke(store)
    }
}
