package zoukan.desktop.discord

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The Discord desktop client's local RPC connection: the named pipe `discord-ipc-N` on Windows,
 * a Unix socket of that name in the temp folder on macOS and Linux. Each frame is a little-endian
 * opcode and length, then JSON. Nothing goes over the network and no Discord login is needed, so
 * on desktop this replaces the Android app's gateway connection with the user's token. Zoukan
 * only talks to a pipe or socket owned by the signed-in user.
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

    private class WindowsPipe(private val handle: WinNT.HANDLE) : Pipe {
        override fun write(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val chunk = bytes.copyOfRange(offset, bytes.size)
                val written = IntByReference()
                if (!Kernel32.INSTANCE.WriteFile(handle, chunk, chunk.size, written, null) || written.value <= 0) {
                    throw IOException("Could not write to Discord")
                }
                offset += written.value
            }
        }

        override fun readFully(count: Int): ByteArray {
            val bytes = ByteArray(count)
            var read = 0
            while (read < count) {
                val buffer = ByteArray(count - read)
                val got = IntByReference()
                if (!Kernel32.INSTANCE.ReadFile(handle, buffer, buffer.size, got, null) || got.value <= 0) {
                    throw IOException("Discord closed the connection")
                }
                buffer.copyInto(bytes, read, 0, got.value)
                read += got.value
            }
            return bytes
        }

        // Cancel first: a thread blocked in ReadFile (the presence read timeout closes the pipe to
        // free it) is released, as closing a file object did
        override fun close() {
            runCatching { PipeApi.INSTANCE.CancelIoEx(handle, null) }
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    @Suppress("FunctionName")
    private interface PipeApi : StdCallLibrary {
        fun GetNamedPipeServerProcessId(pipe: WinNT.HANDLE, serverProcessId: IntByReference): Boolean
        fun CancelIoEx(file: WinNT.HANDLE, overlapped: Pointer?): Boolean

        companion object {
            val INSTANCE: PipeApi by lazy { Native.load("kernel32", PipeApi::class.java, W32APIOptions.DEFAULT_OPTIONS) }
        }
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
            if (windows) {
                for (i in 0..9) openWindows(i)?.let { return DiscordIpc(WindowsPipe(it)) }
            } else {
                for (socket in unixCandidates(unixFolders(), ::ownedByCurrentUser)) {
                    openUnix { it.connect(UnixDomainSocketAddress.of(socket.toPath())) }
                        ?.let { return DiscordIpc(UnixPipe(it)) }
                }
            }
            return null
        }

        // CreateFile flags: the server may learn who connects but cannot act as that user
        private const val SECURITY_SQOS_PRESENT = 0x00100000
        private const val SECURITY_IDENTIFICATION = 0x00010000

        /** The pipe `discord-ipc-[index]` opened at identification level, or null when it is absent or not the user's. */
        private fun openWindows(index: Int): WinNT.HANDLE? = runCatching {
            val handle = Kernel32.INSTANCE.CreateFile(
                "\\\\.\\pipe\\discord-ipc-$index",
                WinNT.GENERIC_READ or WinNT.GENERIC_WRITE,
                0,
                null,
                WinNT.OPEN_EXISTING,
                SECURITY_SQOS_PRESENT or SECURITY_IDENTIFICATION,
                null,
            )
            if (handle == null || handle == WinBase.INVALID_HANDLE_VALUE) return@runCatching null
            val pid = IntByReference()
            val trusted = runCatching {
                PipeApi.INSTANCE.GetNamedPipeServerProcessId(handle, pid) &&
                    servedByCurrentUser(pid.value.toLong() and 0xFFFFFFFFL, ProcessHandle.current().info().user().orElse(null)) {
                        ProcessHandle.of(it).flatMap { p -> p.info().user() }.orElse(null)
                    }
            }.getOrDefault(false)
            if (!trusted) {
                Kernel32.INSTANCE.CloseHandle(handle)
                return@runCatching null
            }
            handle
        }.getOrNull()

        /** The `discord-ipc-N` sockets to try, in order, among [folders], owned by the current user. */
        internal fun unixCandidates(folders: List<File>, ownedByCurrentUser: (Path) -> Boolean): List<File> =
            (0..9).flatMap { i -> folders.map { File(it, "discord-ipc-$i") } }
                .filter { it.exists() && ownedByCurrentUser(it.toPath()) }

        /** Unknown owner means not the user's: the check fails closed. */
        private fun ownedByCurrentUser(path: Path): Boolean = runCatching {
            val me = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
            Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) == me
        }.getOrDefault(false)

        /** True when the process [serverPid] serving a pipe runs as [currentUser]; an unknown owner is refused. */
        internal fun servedByCurrentUser(serverPid: Long, currentUser: String?, userOf: (Long) -> String?): Boolean =
            currentUser != null && userOf(serverPid) == currentUser

        /**
         * Opens a Unix socket channel and runs [connect] on it, or returns null if either fails. A
         * channel whose connect fails is closed: a stale socket file left by a crashed Discord
         * would otherwise leak one channel per retry.
         */
        internal fun openUnix(
            open: () -> SocketChannel = { SocketChannel.open(StandardProtocolFamily.UNIX) },
            connect: (SocketChannel) -> Unit,
        ): SocketChannel? {
            val channel = runCatching(open).getOrNull() ?: return null
            return runCatching { connect(channel) }
                .map { channel }
                .onFailure { runCatching { channel.close() } }
                .getOrNull()
        }

        internal fun unixFolders(env: (String) -> String? = System::getenv): List<File> {
            val bases = listOf("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP").mapNotNull { env(it)?.takeIf(String::isNotBlank) } +
                // The shared /tmp only when no private runtime or temp folder is set: another account
                // could plant a socket there first
                listOf("XDG_RUNTIME_DIR", "TMPDIR").let { private ->
                    if (private.any { !env(it).isNullOrBlank() }) emptyList() else listOf("/tmp")
                }
            // Flatpak and Snap installs put the socket in a subfolder
            return bases.distinct().flatMap { base ->
                listOf("", "app/com.discordapp.Discord", "snap.discord", ".flatpak/com.discordapp.Discord/xdg-run")
                    .map { File(base, it) }
            }
        }
    }
}
