// ZOUKAN -->
package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.BufferedSource
import okio.Sink
import okio.Source
import okio.buffer
import okio.gzip
import java.io.IOException

/**
 * Reads and writes `.tachibk` files: a [Backup] encoded as protobuf, gzipped. Both apps use it, so
 * the format cannot drift between them.
 */
object BackupCodec {

    /** Why a file could not be decoded. */
    enum class Error {
        /** A JSON backup from Tachiyomi before 0.10, which is not supported. */
        JSON,

        /** Not a backup this version understands. */
        UNKNOWN,
    }

    class InvalidBackupException(val error: Error, cause: Throwable? = null) :
        IOException("Invalid backup file: $error", cause)

    /**
     * Decodes a backup, gzipped or not.
     *
     * @throws InvalidBackupException if the data is not a backup.
     */
    fun decode(source: Source, parser: ProtoBuf = ProtoBuf): Backup {
        val bytes = source.buffer().use { it.readBackupBytes() }
        return decode(bytes, parser)
    }

    /** Decodes the protobuf bytes of a backup, already un-gzipped. */
    fun decode(bytes: ByteArray, parser: ProtoBuf = ProtoBuf): Backup {
        return try {
            parser.decodeFromByteArray(Backup.serializer(), bytes)
        } catch (e: SerializationException) {
            throw InvalidBackupException(Error.UNKNOWN, e)
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupException(Error.UNKNOWN, e)
        }
    }

    /** The protobuf bytes of [backup], not gzipped. */
    fun encode(backup: Backup, parser: ProtoBuf = ProtoBuf): ByteArray =
        parser.encodeToByteArray(Backup.serializer(), backup)

    /** Writes [backup] gzipped, as the apps save it. */
    fun write(backup: Backup, sink: Sink, parser: ProtoBuf = ProtoBuf) {
        val bytes = encode(backup, parser)
        sink.gzip().buffer().use { it.write(bytes) }
    }

    private fun BufferedSource.readBackupBytes(): ByteArray {
        val peeked = peek().apply { require(2) }
        val id1id2 = peeked.readShort()
        return when (id1id2.toInt()) {
            GZIP_MAGIC -> gzip().buffer().use { it.readByteArray() }
            MAGIC_JSON_SIGNATURE1, MAGIC_JSON_SIGNATURE2, MAGIC_JSON_SIGNATURE3 ->
                throw InvalidBackupException(Error.JSON)
            else -> readByteArray()
        }
    }

    private const val GZIP_MAGIC = 0x1f8b
    private const val MAGIC_JSON_SIGNATURE1 = 0x7b7d // `{}`
    private const val MAGIC_JSON_SIGNATURE2 = 0x7b22 // `{"`
    private const val MAGIC_JSON_SIGNATURE3 = 0x7b0a // `{\n`
}
// ZOUKAN <--
