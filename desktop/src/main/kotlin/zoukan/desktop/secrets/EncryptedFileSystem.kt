package zoukan.desktop.secrets

import okio.Buffer
import okio.FileHandle
import okio.FileNotFoundException
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import xyz.nulldev.androidcompat.io.sharedprefs.SettingsFileCodec
import java.nio.ByteBuffer
import java.util.UUID

/**
 * A file system whose files are encrypted with [codec] (the file key, see [FileCipher]), for Coil's
 * disk cache: the images fetched by address and the cache's journal and metadata.
 *
 * A file written through [sink] is encrypted whole when its sink is closed (through a temporary file
 * beside it, then moved into place), so a reader never sees half a file. A file written through
 * [appendingSink] (the cache's journal, flushed after each line) is framed instead: [FRAMED_MAGIC],
 * then one frame per flush, each a 4-byte length and that many bytes encrypted by [codec] on their
 * own (its own random nonce, as every file the cipher writes). A flush then only appends a frame.
 * After a crash or a power loss the last frame can be torn (cut short, or left as zeros); it is
 * dropped and the frames before it are kept. Appending to a file encrypted whole (a journal the cache
 * has just rebuilt, or one from before frames) first rewrites it framed, once.
 *
 * A file that cannot be decrypted (a plain one from before encryption, a damaged one, one written
 * with a key that is lost) is deleted and reads as missing: for a cache that is a miss, and the
 * image downloads again. Random access ([openReadOnly], [openReadWrite]) is not supported.
 */
class EncryptedFileSystem(
    delegate: FileSystem,
    private val codec: SettingsFileCodec,
) : ForwardingFileSystem(delegate) {

    override fun source(file: Path): Source = Buffer().write(readPlain(file))

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        if (mustCreate && delegate.exists(file)) throw IOException("$file already exists.")
        return EncryptingSink(file, append = false)
    }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink {
        if (mustExist && !delegate.exists(file)) throw FileNotFoundException("no such file: $file")
        return EncryptingSink(file, append = true)
    }

    override fun openReadOnly(file: Path): FileHandle =
        throw IOException("Encrypted files cannot be opened for random access")

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
        throw IOException("Encrypted files cannot be opened for random access")

    /** [file]'s contents, decrypted; a file that cannot be decrypted is deleted and reads as missing. */
    private fun readPlain(file: Path): ByteArray = readDecoded(file).plain

    /** [file] decrypted, whole or framed; a file that cannot be decrypted is deleted and reads as missing. */
    private fun readDecoded(file: Path): Decoded {
        val stored = delegate.read(file) { readByteArray() }
        val decoded = when {
            isFramed(stored) -> decodeFrames(stored)
            codec.isEncoded(stored) -> runCatching { codec.decode(stored) }.getOrNull()?.let { Decoded(stored, it, framedLength = null) }
            else -> null
        }
        if (decoded == null) {
            delegate.delete(file, mustExist = false)
            throw FileNotFoundException("$file cannot be decrypted; it was deleted")
        }
        return decoded
    }

    /** A file as [stored], decrypted, and for a framed file how many of its bytes are whole frames. */
    private class Decoded(val stored: ByteArray, val plain: ByteArray, val framedLength: Int?)

    /**
     * The frames of a framed file, decrypted, up to the first one that is cut short or cannot be
     * decrypted (a torn last frame after a crash: it and anything after it are dropped). Null when
     * there are bytes after the header but not one frame can be read (damaged, or another key).
     */
    private fun decodeFrames(stored: ByteArray): Decoded? {
        val plain = Buffer()
        var position = FRAMED_MAGIC.size
        var frames = 0
        while (stored.size - position >= LENGTH_BYTES) {
            val length = ByteBuffer.wrap(stored, position, LENGTH_BYTES).int
            val start = position + LENGTH_BYTES
            if (length <= 0 || length > stored.size - start) break
            val frame = runCatching { codec.decode(stored.copyOfRange(start, start + length)) }.getOrNull() ?: break
            plain.write(frame)
            position = start + length
            frames++
        }
        if (frames == 0 && position < stored.size) return null
        return Decoded(stored, plain.readByteArray(), framedLength = position)
    }

    /** [plain] as one frame: its length, then [plain] encrypted with a nonce of its own. */
    private fun frame(plain: ByteArray): ByteArray {
        val encrypted = codec.encode(plain)
        return ByteBuffer.allocate(LENGTH_BYTES).putInt(encrypted.size).array() + encrypted
    }

    /**
     * Makes [file] ready for frames to be appended: a framed file with only whole frames. A missing
     * file is made with just the header; a torn last frame is cut off; a file encrypted whole is
     * rewritten as one frame. Each is written the safe way, through a temporary file. A file that
     * cannot be decrypted is deleted and the append fails, as a read does.
     */
    private fun prepareForAppend(file: Path) {
        if (!delegate.exists(file)) {
            writeStored(file, FRAMED_MAGIC)
            return
        }
        val decoded = readDecoded(file)
        val framedLength = decoded.framedLength
        when {
            framedLength == null -> writeStored(file, FRAMED_MAGIC + frame(decoded.plain))
            framedLength < decoded.stored.size -> writeStored(file, decoded.stored.copyOf(framedLength))
        }
    }

    /**
     * Deletes the temporary files in [dir] that writes cut short left behind (the app was closed or
     * crashed between writing one and moving it into place). Call it before the cache in [dir] is
     * opened, when no write can be under way. Only this file system's own temporary files are
     * deleted; a file that cannot be deleted is left for next time.
     */
    fun deleteTemporaryFiles(dir: Path) {
        val files = try {
            delegate.listOrNull(dir).orEmpty()
        } catch (e: IOException) {
            return
        }
        files.filter { TEMPORARY_FILE.matches(it.name) }.forEach { tmp ->
            try {
                delegate.delete(tmp, mustExist = false)
            } catch (e: IOException) {
                // Kept for the next start
            }
        }
    }

    /** Encrypts [plain] into [file], through a temporary file of its own beside it. */
    private fun writeWhole(file: Path, plain: ByteArray) = writeStored(file, codec.encode(plain))

    /** Writes [stored] into [file] as it is, through a temporary file of its own beside it. */
    private fun writeStored(file: Path, stored: ByteArray) {
        val tmp = file.parent?.resolve("${file.name}.${UUID.randomUUID()}.tmp")
            ?: throw IOException("$file has no parent folder")
        try {
            delegate.write(tmp, mustCreate = true) { write(stored) }
            moveIntoPlace(tmp, file)
        } finally {
            delegate.delete(tmp, mustExist = false)
        }
    }

    /**
     * Moves [tmp] over [file], trying again a few times after a short wait: on Windows a virus
     * scanner or the search indexer can hold either file open for a moment after it is written.
     */
    private fun moveIntoPlace(tmp: Path, file: Path) {
        var attempt = 0
        while (true) {
            try {
                delegate.atomicMove(tmp, file)
                return
            } catch (e: IOException) {
                if (attempt >= MOVE_RETRY_DELAYS_MS.size) throw e
                Thread.sleep(MOVE_RETRY_DELAYS_MS[attempt++])
            }
        }
    }

    /**
     * Collects what is written and encrypts it into the file: whole at [close] for a new file, and
     * as one more frame at each [flush] and at [close] when appending (the cache's journal is
     * flushed after each line).
     */
    private inner class EncryptingSink(private val file: Path, private val append: Boolean) : Sink {
        private val pending = Buffer()

        /** Whether the file was made ready for frames, at the first save of an appending sink. */
        private var prepared = false
        private var closed = false

        override fun write(source: Buffer, byteCount: Long) {
            check(!closed) { "closed" }
            pending.write(source, byteCount)
        }

        override fun flush() {
            check(!closed) { "closed" }
            if (append) save()
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            if (closed) return
            closed = true
            save()
        }

        private fun save() {
            if (!append) {
                writeWhole(file, pending.readByteArray())
                return
            }
            if (!prepared) {
                prepareForAppend(file)
                prepared = true
            }
            if (pending.size == 0L) return
            val frame = frame(pending.readByteArray())
            delegate.appendingSink(file, mustExist = true).buffer().use { it.write(frame) }
        }
    }

    companion object {
        /** The start of a framed file; a file encrypted whole starts with [FileCipher]'s own header. */
        private val FRAMED_MAGIC = "ZOUKANFRM1\n".toByteArray(Charsets.US_ASCII)

        /** The size of each frame's length, before its encrypted bytes. */
        private const val LENGTH_BYTES = 4

        /** Whether [stored] (or its first bytes) is a framed file. */
        private fun isFramed(stored: ByteArray): Boolean =
            stored.size >= FRAMED_MAGIC.size && FRAMED_MAGIC.indices.all { stored[it] == FRAMED_MAGIC[it] }

        /**
         * Whether [header], the first bytes of a file, shows the file is one this file system wrote
         * (encrypted whole by [codec], or framed), not a plain one from before encryption.
         */
        fun isEncrypted(header: ByteArray, codec: SettingsFileCodec): Boolean = isFramed(header) || codec.isEncoded(header)

        /** The waits before each new try of a move that failed. */
        private val MOVE_RETRY_DELAYS_MS = longArrayOf(25, 75, 200)

        /** The temporary files of [writeStored]: the file's name, a random UUID, `.tmp`. */
        private val TEMPORARY_FILE = Regex(""".+\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.tmp""")
    }
}
