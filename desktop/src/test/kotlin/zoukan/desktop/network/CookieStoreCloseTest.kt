package zoukan.desktop.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.app.SelfTest
import java.io.File
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI

/** The self-test deletes its profile while the process runs; nothing may write it back. */
class CookieStoreCloseTest {
    @TempDir
    lateinit var root: File

    private fun cookie(name: String) = HttpCookie(name, "v").apply {
        maxAge = 3600
        path = "/"
        version = 0
    }

    private fun save(store: PersistentCookieStore) {
        PersistentCookieStore::class.java.getDeclaredMethod("save").apply { isAccessible = true }.invoke(store)
    }

    @Test
    fun `close writes the pending change`() {
        val file = File(root, "profile/cookies.json")
        val store = PersistentCookieStore(file, saveDelayMillis = 60_000)
        store.add(URI("https://example.org/"), cookie("session"))
        file.exists() shouldBe false
        store.close()
        file.isFile shouldBe true
        PersistentCookieStore(file).get(URI("https://example.org/")).map { it.name } shouldBe listOf("session")
    }

    @Test
    fun `nothing is written after close, so a deleted folder stays deleted`() {
        val profile = File(root, "profile")
        val store = PersistentCookieStore(File(profile, "cookies.json"), saveDelayMillis = 10)
        store.add(URI("https://example.org/"), cookie("a"))
        store.close()
        profile.deleteRecursively() shouldBe true
        store.add(URI("https://example.org/"), cookie("b"))
        // What the shutdown hook would run
        save(store)
        profile.exists() shouldBe false
        // The store still works in memory
        store.get(URI("https://example.org/")).map { it.name }.sorted() shouldBe listOf("a", "b")
    }

    @Test
    fun `the self-test closes the installed store before deleting its profile`() {
        val previous = CookieHandler.getDefault()
        val profile = File(root, "zoukan-selftest").apply { mkdirs() }
        val store = PersistentCookieStore(File(profile, "cookies.json"), saveDelayMillis = 60_000)
        try {
            CookieHandler.setDefault(CookieManager(store, CookiePolicy.ACCEPT_ALL))
            store.add(URI("https://example.org/"), cookie("a"))
            SelfTest.deleteProfile(profile) shouldBe true
            save(store)
            profile.exists() shouldBe false
        } finally {
            CookieHandler.setDefault(previous)
        }
    }
}
