package zoukan.desktop.testing.fakes

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.IOException
import okio.Path
import okio.Sink
import okio.sink
import zoukan.desktop.secrets.SecretStore
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.sql.SQLException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A secret store in memory, for tests. [values] is the stored state, which a test may read or change. */
class MemorySecretStore(override val name: String = "test secret store") : SecretStore {
    val values: MutableMap<String, String> = ConcurrentHashMap()

    override fun get(key: String): String? = values[key]

    override fun set(key: String, value: String) {
        values[key] = value
    }

    override fun delete(key: String) {
        values.remove(key)
    }
}

/**
 * A secret store that fails on demand, the way the OS stores fail: their errors are
 * IllegalStateExceptions that carry the native status (see MacKeychainStore and
 * WindowsCredentialStore). Reads, writes and deletes fail separately, and each can first succeed a
 * given number of times, for a failure midway (the key is read, a later write fails).
 *
 * A corrupt or garbled value is a value put in [backing] directly.
 */
class FailingSecretStore(val backing: MemorySecretStore = MemorySecretStore()) : SecretStore {

    /** The ways an OS store fails, with the error each one reports. */
    enum class Failure(private val macStatus: Int?, private val windowsError: Int?) {
        /** macOS: the login keychain is locked and no prompt may be shown (errSecInteractionNotAllowed). */
        LOCKED(macStatus = -25308, windowsError = null),

        /** macOS: the user chose Deny in the Keychain prompt (errSecUserCanceled); Windows: access denied. */
        DENIED(macStatus = -128, windowsError = 5),

        /** Windows: no Credential Manager in this logon session, as over SSH (ERROR_NO_SUCH_LOGON_SESSION). */
        UNAVAILABLE(macStatus = null, windowsError = 1312),
        ;

        fun exception(operation: Operation): IllegalStateException = when {
            macStatus != null -> IllegalStateException("${operation.macCall} failed with status $macStatus")
            else -> IllegalStateException("${operation.windowsCall} failed with error $windowsError")
        }
    }

    enum class Operation(val macCall: String, val windowsCall: String) {
        READ("SecKeychainFindGenericPassword", "CredReadW"),
        WRITE("SecKeychainAddGenericPassword", "CredWriteW"),
        DELETE("SecKeychainItemDelete", "CredDeleteW"),
    }

    private class Plan(val failure: Failure, val succeedFirst: Int)

    override val name = "failing test store"

    private val plans = mutableMapOf<Operation, Plan>()
    private val counts = Operation.entries.associateWith { AtomicInteger() }

    /** Every key a write was attempted for, in order, whether or not it failed. */
    val attemptedWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Makes [operation] fail with [failure] after it has succeeded [succeedFirst] times from now. */
    @Synchronized
    fun failOn(operation: Operation, failure: Failure, succeedFirst: Int = 0): FailingSecretStore = apply {
        counts.getValue(operation).set(0)
        plans[operation] = Plan(failure, succeedFirst)
    }

    /** Makes every operation fail with [failure]. */
    fun failAll(failure: Failure): FailingSecretStore = apply { Operation.entries.forEach { failOn(it, failure) } }

    @Synchronized
    fun heal(): FailingSecretStore = apply { plans.clear() }

    @Synchronized
    private fun check(operation: Operation) {
        val plan = plans[operation] ?: return
        if (counts.getValue(operation).getAndIncrement() >= plan.succeedFirst) throw plan.failure.exception(operation)
    }

    override fun get(key: String): String? {
        check(Operation.READ)
        return backing.get(key)
    }

    override fun set(key: String, value: String) {
        attemptedWrites += key
        check(Operation.WRITE)
        backing.set(key, value)
    }

    override fun delete(key: String) {
        check(Operation.DELETE)
        backing.delete(key)
    }
}

/**
 * A [SqlDriver] over a real one ([delegate]) that fails a statement on demand, the way SQLite fails
 * one (a full disk, a corrupt page, a busy database): the database the app opens with
 * `DesktopDatabase.createDatabase(driver)` and the `AndroidDatabaseHandler` over it work as usual
 * until [failOn]'s statement, which throws [SQLException] instead of running.
 *
 * Transactions are the delegate's own (BEGIN, COMMIT and ROLLBACK do not go through [execute]), so a
 * failure inside a transaction rolls it back exactly as a real failure does. [heal] ends the failure.
 *
 * ```
 * val driver = FailingSqlDriver(JdbcSqliteDriver(...))
 * driver.failOn(FailingSqlDriver.insertInto("chapters"))
 * ```
 */
class FailingSqlDriver(private val delegate: SqlDriver) : SqlDriver by delegate {

    private class Plan(val matches: (String) -> Boolean, val nth: Int, var times: Int, val error: (String) -> Exception) {
        var seen = 0
    }

    private var plan: Plan? = null

    /** Every statement and query run through this driver, in order, failed ones too. */
    val statements: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** How many statements [failOn] has failed so far. */
    @Volatile
    var failures = 0
        private set

    /**
     * Fails the [nth] statement or query from now whose SQL [matches] (by default any), and the next
     * [times] - 1 matching ones after it, with [error].
     */
    @Synchronized
    fun failOn(
        matches: (String) -> Boolean = { true },
        nth: Int = 1,
        times: Int = 1,
        error: (String) -> Exception = { SQLException("[SQLITE_FULL] database or disk is full (injected)") },
    ): FailingSqlDriver = apply {
        require(nth > 0 && times > 0) { "nth and times must be positive" }
        plan = Plan(matches, nth, times, error)
    }

    @Synchronized
    fun heal(): FailingSqlDriver = apply { plan = null }

    @Synchronized
    private fun check(sql: String) {
        statements += sql
        val current = plan ?: return
        if (!current.matches(sql)) return
        current.seen++
        if (current.seen >= current.nth && current.times > 0) {
            current.times--
            if (current.times == 0) plan = null
            failures++
            throw current.error(sql)
        }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        check(sql)
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        check(sql)
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    companion object {
        /** Matches an INSERT into [table], however the generated SQL is spaced. */
        fun insertInto(table: String): (String) -> Boolean {
            val pattern = Regex("""(?is)^\s*INSERT\s+(OR\s+\w+\s+)?INTO\s+`?$table`?\b""")
            return { pattern.containsMatchIn(it) }
        }

        /** Matches a SELECT that reads [table]. */
        fun selectFrom(table: String): (String) -> Boolean {
            val pattern = Regex("""(?is)^\s*SELECT\b.*\bFROM\s+`?$table`?\b""")
            return { pattern.containsMatchIn(it) }
        }
    }
}

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

/**
 * Real file-system failures for code that uses `java.io.File` or `java.nio.file` directly (the
 * downloads, backups, the update and extension downloads), which no fake file system can intercept:
 *
 * - [fileInTheWay] puts a regular file where a folder is needed, so creating that folder or anything
 *   in it fails. It works on every OS and for every user, root too.
 * - [readOnly] makes a folder read-only for this process. It works only where the OS enforces it:
 *   not on Windows (a folder's read-only flag does not stop new files there) and not for root, so it
 *   returns null there and the test is skipped (`assumeTrue(fault != null)`).
 *
 * What cannot be made real this way, and needs a seam in the code under test instead: a disk that is
 * full (`No space left on device`) and a write that fails part way through a file. Use
 * [FaultyFileSystem] where the code takes an okio file system, or the code's own write hook (as
 * [zoukan.desktop.download.ChapterDownloader]'s `writeEntry`).
 */
object FileFaults {

    /** A folder made read-only by [readOnly]; [close] gives it its permissions back. */
    class ReadOnlyFolder internal constructor(val dir: File, private val before: Set<PosixFilePermission>) : AutoCloseable {
        override fun close() {
            Files.setPosixFilePermissions(dir.toPath(), before)
        }
    }

    /**
     * Makes [dir] (created if missing) read-only, or returns null when this OS or user can still
     * write in it. Close the result to heal.
     */
    fun readOnly(dir: File): ReadOnlyFolder? {
        dir.mkdirs()
        val path = dir.toPath()
        if ("posix" !in path.fileSystem.supportedFileAttributeViews()) return null
        val before = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r-xr-xr-x"))
        val folder = ReadOnlyFolder(dir, before)
        // Root writes anyway: then the condition cannot be made here
        val probe = try {
            Files.createTempFile(path, "probe", null)
        } catch (e: java.io.IOException) {
            null
        }
        if (probe != null) {
            Files.delete(probe)
            folder.close()
            return null
        }
        return folder
    }

    /**
     * Puts a regular file at [path] (making its parent folders), so making [path] a folder, or
     * anything under it, fails on every OS. Fails when something is already at [path]. Delete the
     * returned file to heal.
     */
    fun fileInTheWay(path: File): File {
        check(!path.exists()) { "$path already exists" }
        path.absoluteFile.parentFile.mkdirs()
        path.writeText("A file where a folder is needed")
        return path
    }
}

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
        throw java.io.IOException(DISK_FULL)
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
