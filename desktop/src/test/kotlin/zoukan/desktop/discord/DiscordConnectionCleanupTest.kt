package zoukan.desktop.discord

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.io.path.createDirectories

class DiscordConnectionCleanupTest {
    @Test
    fun `a socket owned by another user is skipped`(@TempDir root: Path) {
        val shared = root.resolve("shared").createDirectories().toFile()
        val private = root.resolve("private").createDirectories().toFile()
        File(shared, "discord-ipc-0").apply { writeText("") }
        val real = File(private, "discord-ipc-0").apply { writeText("") }
        DiscordIpc.unixCandidates(listOf(shared, private)) { it == real.toPath() } shouldBe listOf(real)
    }

    @Test
    fun `the shared tmp folder is not searched when a private temp folder is set`() {
        val env = mapOf("TMPDIR" to "/private/var/folders/xy/T")
        DiscordIpc.unixFolders(env::get).map { it.path.replace('\\', '/') }
            .none { it == "/tmp" || it.startsWith("/tmp/") } shouldBe true
    }

    @Test
    fun `a pipe handle is released once however often the connection is closed`() {
        var released = 0
        val guard = DiscordIpc.CloseOnce { released++ }
        guard.isClosed shouldBe false
        guard.close()
        guard.close()
        released shouldBe 1
        guard.isClosed shouldBe true
    }

    @Test
    fun `a pipe served by another user's process is refused`() {
        val users = mapOf(10L to "PC\\alice", 20L to "PC\\mallory")
        DiscordIpc.servedByCurrentUser(10L, "PC\\alice", users::get) shouldBe true
        DiscordIpc.servedByCurrentUser(20L, "PC\\alice", users::get) shouldBe false
        DiscordIpc.servedByCurrentUser(30L, "PC\\alice", users::get) shouldBe false // owner unknown: refuse
        DiscordIpc.servedByCurrentUser(10L, null, users::get) shouldBe false
    }

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
