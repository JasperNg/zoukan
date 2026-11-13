package zoukan.desktop.discord

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.StandardProtocolFamily
import java.nio.channels.SocketChannel

class DiscordConnectionCleanupTest {
    @Test
    fun `a channel whose connect fails is closed`() {
        var opened: SocketChannel? = null
        val result = DiscordIpc.openUnix(
            open = { SocketChannel.open(StandardProtocolFamily.UNIX).also { opened = it } },
            connect = { throw IOException("stale socket") },
        )
        result.shouldBeNull()
        opened!!.isOpen shouldBe false
    }

    @Test
    fun `a failed handshake closes the connection and rethrows`() {
        var closed = false
        shouldThrow<IllegalStateException> {
            DiscordPresence.handshake("connection", close = { closed = true }) { error("refused") }
        }
        closed shouldBe true
    }

    @Test
    fun `a good handshake keeps the connection open`() {
        var closed = false
        DiscordPresence.handshake("connection", close = { closed = true }) { } shouldBe "connection"
        closed shouldBe false
    }
}
