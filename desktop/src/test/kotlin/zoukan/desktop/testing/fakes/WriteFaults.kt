package zoukan.desktop.testing.fakes

import okio.Buffer
import okio.ForwardingSink
import okio.Sink
import okio.sink
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * A full disk and a write that fails part way through a file, for code that writes with
 * `java.io.File` through a seam: a factory that opens the file's sink or stream (as
 * [zoukan.desktop.backup.DesktopBackups]' `openSink` and
 * [zoukan.desktop.download.ChapterDownloader]'s `openOutput`). Pass [sink] or [outputStream] as that
 * factory. While [fault] is null every file is written as usual; set it to fail the files [only]
 * accepts, and set it back to null to heal.
 *
 * The data written before the fault reaches the file, so the code under test sees what a real full
 * disk leaves: a file with its first bytes and no more.
 */
class WriteFaults {

    /** Where a write fails. */
    sealed interface Fault {
        /** Opening the file fails: the disk is already full. */
        data object AtOpen : Fault

        /** The first [bytes] bytes of the file are written, then the write fails: the disk fills up. */
        data class AfterBytes(val bytes: Long) : Fault

        /** Every byte is written, but closing the file (the last flush) fails. */
        data object AtClose : Fault
    }

    @Volatile
    var fault: Fault? = null

    /** Which files fail; others are written as usual. */
    @Volatile
    var only: (File) -> Boolean = { true }

    private val hits = AtomicInteger()

    /** How many times a write failed on purpose. */
    val failures: Int get() = hits.get()

    private fun faultFor(file: File): Fault? = fault?.takeIf { only(file) }

    private fun fail(): Nothing {
        hits.incrementAndGet()
        throw IOException(DISK_FULL)
    }

    /** Opens [file] as an okio sink, with the current fault. */
    fun sink(file: File): Sink {
        val fault = faultFor(file)
        if (fault == Fault.AtOpen) fail()
        val delegate = file.sink()
        return when (fault) {
            null -> delegate
            else -> object : ForwardingSink(delegate) {
                private var written = 0L
                private var closed = false

                override fun write(source: Buffer, byteCount: Long) {
                    if (fault is Fault.AfterBytes && written + byteCount > fault.bytes) {
                        val room = fault.bytes - written
                        if (room > 0) super.write(source, room)
                        written = fault.bytes
                        fail()
                    }
                    written += byteCount
                    super.write(source, byteCount)
                }

                // Closed twice by layered sinks: it fails once, as a file's last flush does
                override fun close() {
                    if (closed) return
                    closed = true
                    super.close()
                    if (fault == Fault.AtClose) fail()
                }
            }
        }
    }

    /** Opens [file] as an output stream, with the current fault. */
    fun outputStream(file: File): OutputStream {
        val fault = faultFor(file)
        if (fault == Fault.AtOpen) fail()
        val delegate = file.outputStream()
        return when (fault) {
            null -> delegate
            else -> object : FilterOutputStream(delegate) {
                private var written = 0L
                private var closed = false

                override fun write(b: Int) {
                    write(byteArrayOf(b.toByte()), 0, 1)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (fault is Fault.AfterBytes && written + len > fault.bytes) {
                        val room = (fault.bytes - written).toInt()
                        if (room > 0) out.write(b, off, room)
                        written = fault.bytes
                        fail()
                    }
                    written += len
                    out.write(b, off, len)
                }

                override fun close() {
                    if (closed) return
                    closed = true
                    super.close()
                    if (fault == Fault.AtClose) fail()
                }
            }
        }
    }

    companion object {
        /** The message of the error a full disk gives on Linux and macOS. */
        const val DISK_FULL = "No space left on device"
    }
}
