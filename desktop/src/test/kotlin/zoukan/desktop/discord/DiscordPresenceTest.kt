package zoukan.desktop.discord

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.security.Incognito
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DiscordPresenceTest {
    private val root = Files.createTempDirectory("zoukan-discord").toFile()
    private val store by lazy {
        System.setProperty("$CONFIG_PREFIX.server.rootDir", root.absolutePath)
        DesktopPreferenceStore(JavaSharedPreferences("discord_test_${System.nanoTime()}"))
    }

    @Test
    fun `turning incognito on hides what is read at once`() {
        var incognitoOn = false
        val incognito = mockk<Incognito>(relaxed = true)
        every { incognito.isOn(any()) } answers { incognitoOn }
        val getCategories = mockk<GetCategories>()
        coEvery { getCategories.await(any<Long>()) } returns emptyList()
        val presence = DiscordPresence(store, getCategories, incognito)
        presence.setReading(DiscordPresence.Reading(1, "Secret Title", "Chapter 3", 3.0, null, sourceId = 5))

        runBlocking { presence.activity() }["details"]!!.jsonPrimitive.content shouldBe "Secret Title"
        incognitoOn = true
        val hidden = runBlocking { presence.activity() }
        hidden["details"]!!.jsonPrimitive.content shouldBe "Reading"
        hidden.containsKey("state") shouldBe false
    }

    @Test
    fun `a Discord app that stops answering times out and the connection is closed`() {
        val closed = CountDownLatch(1)
        val error = shouldThrow<IllegalStateException> {
            runBlocking {
                DiscordPresence.readWithTimeout(
                    timeout = 200,
                    close = { closed.countDown() },
                    // A read that blocks until the connection closes, as a pipe read does
                    read = {
                        closed.await(10, TimeUnit.SECONDS)
                        error("closed")
                    },
                )
            }
        }
        error.message shouldBe "Discord did not answer in time"
        closed.count shouldBe 0L
    }

    @Test
    fun `an answer in time is returned`() {
        val reply = runBlocking { DiscordPresence.readWithTimeout(1_000, close = {}, read = { 1 to "{}" }) }
        reply shouldBe (1 to "{}")
    }
}
