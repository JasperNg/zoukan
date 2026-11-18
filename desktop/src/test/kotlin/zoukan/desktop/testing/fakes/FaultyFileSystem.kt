package zoukan.desktop.testing.fakes

import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.IOException
import okio.Path
import okio.Sink
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * An okio [FileSystem] over [delegate] (by default the real one) that fails on demand, for code
 * that takes an okio file system (the encrypted image cache, [zoukan.desktop.secrets.EncryptedFileSystem]):
 *
 * - [failOn] fails the n-th call of an [Op] (the n-th `write` to any open file, the n-th move...),
 *   a given number of times;
 * - [full] is a disk with only so many bytes free: a write past them writes what fits, as a real
 *   disk does, and then throws `No space left on device`;
 * - [readOnly] refuses every change (`Read-only file system`); reading still works.
 *
 * [heal] ends every failure, so a test can check that a retry works. Code that uses `java.io.File`
 * or `java.nio.file` directly cannot be given this; see [FileFaults] for the real conditions that
 * work there.
 */
class FaultyFileSystem(delegate: FileSystem = FileSystem.SYSTEM) : ForwardingFileSystem(delegate) {

    /** What [failOn] can fail. */
    enum class Op {
        /** Opening a file for writing ([sink] or [appendingSink]). */
        OPEN_FOR_WRITE,

        /** One `write` call on any file opened for writing. */
        WRITE,
        MOVE,
        DELETE,
        CREATE_DIRECTORY,
    }

    private class Plan(val nth: Int, var times: Int, val error: (Path) -> IOException)

    private val plans = mutableMapOf<Op, Plan>()
    private val counts = Op.entries.associateWith { AtomicInteger() }

    @Volatile
    private var readOnly = false

    /** Bytes that may still be written; null when the disk is not full. */
    private val free = AtomicLong(-1)

    /** Bytes written through this file system, to files that took them. */
    val bytesWritten = AtomicLong()

    /** How many times [op] was called since this file system was made. */
    fun calls(op: Op): Int = counts.getValue(op).get()

    /**
     * Fails the [nth] call of [op] from now (1 is the next one) and the next [times] - 1 after it,
     * with [error]. Calls before and after go through.
     */
    @Synchronized
    fun failOn(
        op: Op,
        nth: Int = 1,
        times: Int = 1,
        error: (Path) -> IOException = { IOException("Injected ${op.name.lowercase()} failure: $it") },
    ): FaultyFileSystem = apply {
        require(nth > 0 && times > 0) { "nth and times must be positive" }
        plans[op] = Plan(counts.getValue(op).get() + nth, times, error)
    }

    /** A disk with [freeBytes] free from now: writes past them fail with `No space left on device`. */
    fun full(freeBytes: Long = 0): FaultyFileSystem = apply {
        require(freeBytes >= 0)
        free.set(freeBytes)
    }

    /** Refuses every change from now with `Read-only file system`. */
    fun readOnly(): FaultyFileSystem = apply { readOnly = true }

    /** Ends every failure: the disk has room, is writable and no call is planned to fail. */
    @Synchronized
    fun heal(): FaultyFileSystem = apply {
        plans.clear()
        readOnly = false
        free.set(-1)
    }

    @Synchronized
    private fun check(op: Op, path: Path) {
        val call = counts.getValue(op).incrementAndGet()
        val plan = plans[op] ?: return
        if (call >= plan.nth && plan.times > 0) {
            plan.times--
            if (plan.times == 0) plans.remove(op)
            throw plan.error(path)
        }
    }

    private fun refuseIfReadOnly(path: Path) {
        if (readOnly) throw IOException("Read-only file system: $path")
    }

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        refuseIfReadOnly(file)
        check(Op.OPEN_FOR_WRITE, file)
        return FaultySink(super.sink(file, mustCreate), file)
    }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink {
        refuseIfReadOnly(file)
        check(Op.OPEN_FOR_WRITE, file)
        return FaultySink(super.appendingSink(file, mustExist), file)
    }

    override fun atomicMove(source: Path, target: Path) {
        refuseIfReadOnly(target)
        check(Op.MOVE, target)
        super.atomicMove(source, target)
    }

    override fun delete(path: Path, mustExist: Boolean) {
        refuseIfReadOnly(path)
        check(Op.DELETE, path)
        super.delete(path, mustExist)
    }

    override fun createDirectory(dir: Path, mustCreate: Boolean) {
        refuseIfReadOnly(dir)
        check(Op.CREATE_DIRECTORY, dir)
        super.createDirectory(dir, mustCreate)
    }

    override fun createSymlink(source: Path, target: Path) {
        refuseIfReadOnly(source)
        super.createSymlink(source, target)
    }

    private inner class FaultySink(delegate: Sink, private val file: Path) : ForwardingSink(delegate) {
        override fun write(source: Buffer, byteCount: Long) {
            refuseIfReadOnly(file)
            check(Op.WRITE, file)
            val room = free.get()
            if (room in 0 until byteCount) {
                // What fits is written, as on a real disk, then the write fails
                if (room > 0) {
                    super.write(source, room)
                    bytesWritten.addAndGet(room)
                }
                free.set(0)
                throw IOException(DISK_FULL)
            }
            super.write(source, byteCount)
            bytesWritten.addAndGet(byteCount)
            if (room >= 0) free.addAndGet(-byteCount)
        }
    }

    companion object {
        /** The message a full disk gives on Linux and macOS (ENOSPC); the app looks for it. */
        const val DISK_FULL = "No space left on device"
    }
}
