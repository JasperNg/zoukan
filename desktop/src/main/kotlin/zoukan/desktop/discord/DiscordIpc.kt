package zoukan.desktop.discord

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

/**
 * The Discord desktop client's local RPC connection: the named pipe `discord-ipc-N` on Windows,
 * a Unix socket of that name in the temp folder on macOS and Linux. Each frame is a little-endian
 * opcode and length, then JSON. Nothing goes over the network and no Discord login is needed, so
 * on desktop this replaces the Android app's gateway connection with the user's token.
 */
class DiscordIpc private constructor(private val pipe: Pipe) : Closeable {

    fun send(op: Int, json: String) {
        val payload = json.toByteArray(Charsets.UTF_8)
        val frame = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        frame.putInt(op).putInt(payload.size).put(payload)
        pipe.write(frame.array())
    }

    /** Reads one frame: its opcode and JSON. */
    fun read(): Pair<Int, String> {
        val header = ByteBuffer.wrap(pipe.readFully(8)).order(ByteOrder.LITTLE_ENDIAN)
        val op = header.int
        val length = header.int
        require(length in 0..MAX_FRAME) { "Bad Discord frame length $length" }
        return op to String(pipe.readFully(length), Charsets.UTF_8)
    }

    override fun close() = pipe.close()

    private interface Pipe : Closeable {
        fun write(bytes: ByteArray)
        fun readFully(count: Int): ByteArray
    }

    private class WindowsPipe(private val file: RandomAccessFile) : Pipe {
        override fun write(bytes: ByteArray) = file.write(bytes)
        override fun readFully(count: Int) = ByteArray(count).also { file.readFully(it) }
        override fun close() = file.close()
    }

    private class UnixPipe(private val channel: SocketChannel) : Pipe {
        private val input = Channels.newInputStream(channel)
        override fun write(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        override fun readFully(count: Int): ByteArray {
            val bytes = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(bytes, read, count - read)
                if (n < 0) error("Discord closed the connection")
                read += n
            }
            return bytes
        }
        override fun close() = channel.close()
    }

    companion object {
        const val OP_HANDSHAKE = 0
        const val OP_FRAME = 1
        const val OP_CLOSE = 2
        private const val MAX_FRAME = 1 shl 20

        /** Connects to the first running Discord client, or returns null when none runs. */
        fun connect(): DiscordIpc? {
            val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
            for (i in 0..9) {
                if (windows) {
                    runCatching { RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw") }
                        .getOrNull()?.let { return DiscordIpc(WindowsPipe(it)) }
                } else {
                    for (folder in unixFolders()) {
                        val socket = File(folder, "discord-ipc-$i")
                        if (!socket.exists()) continue
                        runCatching {
                            SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(socket.toPath())) }
                        }.getOrNull()?.let { return DiscordIpc(UnixPipe(it)) }
                    }
                }
            }
            return null
        }

        private fun unixFolders(): List<File> {
            val bases = listOf("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP").mapNotNull { System.getenv(it) } + "/tmp"
            // Flatpak and Snap installs put the socket in a subfolder
            return bases.distinct().flatMap { base ->
                listOf("", "app/com.discordapp.Discord", "snap.discord", ".flatpak/com.discordapp.Discord/xdg-run")
                    .map { File(base, it) }
            }
        }
    }
}
