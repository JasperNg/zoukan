package zoukan.desktop.app

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * One Zoukan window per data folder. With "keep running in the tray", opening Zoukan again (its
 * icon, the Start menu, the Dock) is how people bring the window back, and a second app on the
 * same folder would run a second download queue, library update and database writer.
 *
 * The first instance holds an exclusive lock on `<data>/zoukan.lock` and listens on a loopback
 * port written to `<data>/zoukan.port` with a random token. A later one finds the lock held, sends
 * "show <token>" to that port so the running window comes to the front, and exits.
 */
class SingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val server: ServerSocket,
    private val portFile: File,
) : AutoCloseable {

    override fun close() {
        held.remove(this)
        runCatching { server.close() }
        runCatching { portFile.delete() }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    sealed interface Result {
        /** This is the only instance; keep it open while the app runs. */
        class Acquired(val instance: SingleInstance) : Result

        /** Another instance runs; [shown] tells whether it was asked to show its window. */
        data class AlreadyRunning(val shown: Boolean) : Result
    }

    companion object {
        private const val LOCK = "zoukan.lock"
        private const val PORT = "zoukan.port"

        /**
         * Instances that hold their lock. The lock lives as long as its channel, and a channel
         * nothing refers to is closed by the garbage collector, which would free the folder for
         * a second Zoukan minutes into the run; this keeps them reachable until [close].
         */
        private val held: MutableSet<SingleInstance> = ConcurrentHashMap.newKeySet()

        /** Closes every held instance; for tests, which must free the folder to delete it. */
        internal fun closeAll() {
            held.toList().forEach { it.close() }
        }

        /** Takes the lock for [root], or asks the instance holding it to [onShow] its window. */
        fun acquire(root: File, onShow: () -> Unit): Result {
            val (channel, lock) = takeLock(root)
                ?: return Result.AlreadyRunning(shown = signal(File(root, PORT)))
            val token = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })
            val server = ServerSocket()
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4)
            val portFile = File(root, PORT)
            portFile.writeText("${server.localPort} $token")
            Thread({ listen(server, token, onShow) }, "zoukan-single-instance").apply { isDaemon = true }.start()
            val instance = SingleInstance(channel, lock, server, portFile)
            held += instance
            return Result.Acquired(instance)
        }

        /**
         * Takes the lock for [root] for a CLI command that needs the folder to itself (it starts
         * Chromium or writes the cookie store), or returns null when Zoukan already holds it —
         * without showing its window, which a command has no use for. The command closes the
         * returned instance when it ends, freeing the folder again.
         */
        fun acquireForCommand(root: File): SingleInstance? {
            val (channel, lock) = takeLock(root) ?: return null
            // No port file and no listener: Zoukan finding this lock later can only say that
            // something is running, there is no window of ours to bring up
            val instance = SingleInstance(channel, lock, ServerSocket(), File(root, PORT))
            held += instance
            return instance
        }

        /** Opens the folder's lock file and takes its lock; null (channel closed) when it is held. */
        private fun takeLock(root: File): Pair<FileChannel, FileLock>? {
            root.mkdirs()
            val channel = RandomAccessFile(File(root, LOCK), "rw").channel
            // tryLock throws only when this JVM holds the lock; another process holding it
            // (a running Zoukan, a CLI command) makes it return null
            val lock: FileLock? = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return channel to lock
        }

        private fun listen(server: ServerSocket, token: String, onShow: () -> Unit) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: continue
                socket.use {
                    it.soTimeout = 2000
                    val line = runCatching { it.getInputStream().bufferedReader().readLine() }.getOrNull()
                    if (line == "show $token") {
                        runCatching { onShow() }
                        runCatching { it.getOutputStream().write("ok\n".toByteArray()) }
                    }
                }
            }
        }

        private fun signal(portFile: File): Boolean = runCatching {
            val (port, token) = portFile.readText().trim().split(' ', limit = 2)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port.toInt()), 2000)
                socket.soTimeout = 3000
                socket.getOutputStream().write("show $token\n".toByteArray())
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader().readLine() == "ok"
            }
        }.getOrDefault(false)
    }
}
