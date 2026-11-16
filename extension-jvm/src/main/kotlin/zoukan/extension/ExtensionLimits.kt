package zoukan.extension

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Size limits for what a store serves, applied before the trust decision: any added store can serve
 * a huge file or a zip bomb, and parsing it must not fill the disk or the memory. Real extensions
 * (jars and APKs of a few MB) are far below these.
 */
internal object ExtensionLimits {
    const val MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024
    const val MAX_ENTRIES = 20_000
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    fun megabytes(bytes: Long) = bytes / (1024 * 1024)
}

/**
 * Reads an installed APK. The installer never writes one larger than a download may be, so a
 * larger file is not ours and is refused before it is read into memory.
 */
internal fun readInstalledApk(apk: File): ByteArray {
    if (apk.length() > ExtensionLimits.MAX_DOWNLOAD_BYTES) {
        throw ExtensionException(
            ExtensionException.Stage.SIGNATURE,
            "${apk.name} is too large (over ${ExtensionLimits.megabytes(ExtensionLimits.MAX_DOWNLOAD_BYTES)} MB)",
        )
    }
    return apk.readBytes()
}

/**
 * Reads this stream to the end, at most [limit] bytes: a longer stream fails with an
 * [ExtensionException] of [stage] naming [what] and the limit, without reading the rest.
 */
internal fun InputStream.readAtMost(limit: Long, stage: ExtensionException.Stage, what: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    copyAtMost(out, limit, stage, what)
    return out.toByteArray()
}

/** Copies this stream to [out], at most [limit] bytes; see [readAtMost]. Returns the bytes copied. */
internal fun InputStream.copyAtMost(out: OutputStream, limit: Long, stage: ExtensionException.Stage, what: String): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return total
        total += read
        if (total > limit) {
            throw ExtensionException(stage, "$what is too large (over ${ExtensionLimits.megabytes(limit)} MB)")
        }
        out.write(buffer, 0, read)
    }
}

/** Counts the entries and bytes of a zip that is read, and refuses one past the limits. */
internal class ZipBudget(private val stage: ExtensionException.Stage, private val what: String) {
    private var entries = 0
    private var total = 0L

    /** Reads the current entry of [input] within what is left of the budget. */
    fun read(name: String, input: InputStream): ByteArray {
        if (++entries > ExtensionLimits.MAX_ENTRIES) {
            throw ExtensionException(stage, "$what has too many entries (over ${ExtensionLimits.MAX_ENTRIES})")
        }
        val bytes = input.readAtMost(
            minOf(ExtensionLimits.MAX_ENTRY_BYTES, ExtensionLimits.MAX_TOTAL_BYTES - total),
            stage,
            "$what entry $name",
        )
        total += bytes.size
        return bytes
    }
}
