package zoukan.desktop.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.ConnectionManager.Transaction
import app.cash.sqldelight.driver.jdbc.JdbcCursor
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import app.cash.sqldelight.driver.jdbc.JdbcPreparedStatement
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.Properties
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SQLDelight's `JdbcSqliteDriver` for a database file, except that the [databaseThread] keeps its
 * connection and prepared statements while it is busy.
 *
 * `JdbcSqliteDriver` opens a connection for each statement or transaction and closes it after. On
 * the encrypted database that means applying the key and reading the schema each time, and each
 * close of the last connection checkpoints the WAL into the file: two thirds of a backup restore's
 * time, which runs one transaction per entry. It also prepares every statement again each time.
 *
 * Here the database thread's connection stays open until the thread has not used it for
 * [idleCloseMs], and is then closed as before (checkpointing the WAL), so the file at rest is the
 * same. Its statements are kept by SQL, as Android's SQLite does, and closed with it. Other threads
 * open and close a connection per use, as `JdbcSqliteDriver` does. Durability settings
 * (`synchronous`, the journal mode) are unchanged.
 *
 * Transactions and listeners work as in `JdbcSqliteDriver` 2.3.2.
 */
class DesktopSqliteDriver(
    private val url: String,
    private val properties: Properties,
    private val databaseThread: DatabaseThread?,
    private val idleCloseMs: Long = 1_000,
) : JdbcDriver() {

    private val transactions = ThreadLocal<Transaction>()
    private val connections = ThreadLocal<Connection>()

    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()

    // Guarded by listeners
    private var deferring = 0
    private val pendingKeys = linkedSetOf<String>()
    private var flushScheduled = false

    // Read and written on the database thread only
    private var kept: Connection? = null
    private var lastUse = 0L
    private var closeScheduled = false

    /** [kept]'s prepared statements by SQL, least recently used first. Closed with it. */
    private val statements = object : LinkedHashMap<String, PreparedStatement>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreparedStatement>): Boolean {
            if (size <= MAX_STATEMENTS) return false
            runCatching { eldest.value.close() }
            return true
        }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners.getOrPut(it) { linkedSetOf() }.add(listener) }
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.remove(listener) }
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = linkedSetOf<Query.Listener>()
        synchronized(listeners) {
            if (deferring > 0 && databaseThread != null) {
                pendingKeys.addAll(queryKeys)
                if (flushScheduled) return
                flushScheduled = try {
                    databaseThread.executor.schedule(::flushNotifications, NOTIFY_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    true
                } catch (_: RejectedExecutionException) {
                    // Shutting down: tell them now
                    false
                }
                if (flushScheduled) return
                pendingKeys.forEach { listeners[it]?.let(toNotify::addAll) }
                pendingKeys.clear()
            } else {
                queryKeys.forEach { listeners[it]?.let(toNotify::addAll) }
            }
        }
        toNotify.forEach(Query.Listener::queryResultsChanged)
    }

    /**
     * Until the returned handle is closed, tells the queries the app watches that their tables
     * changed at most every [NOTIFY_INTERVAL_MS], and once more at the end, instead of after every
     * transaction. For a backup restore, whose one transaction per entry would otherwise make an
     * open Library tab read the whole library again after each entry, on the same database thread.
     * What is written, and when, does not change.
     */
    fun deferNotifications(): AutoCloseable {
        synchronized(listeners) { deferring++ }
        var closed = false
        return AutoCloseable {
            if (closed) return@AutoCloseable
            closed = true
            synchronized(listeners) { deferring-- }
            flushNotifications()
        }
    }

    private fun flushNotifications() {
        val keys = synchronized(listeners) {
            flushScheduled = false
            pendingKeys.toTypedArray().also { pendingKeys.clear() }
        }
        if (keys.isNotEmpty()) {
            val toNotify = linkedSetOf<Query.Listener>()
            synchronized(listeners) { keys.forEach { listeners[it]?.let(toNotify::addAll) } }
            toNotify.forEach(Query.Listener::queryResultsChanged)
        }
    }

    override var transaction: Transaction?
        get() = transactions.get()
        set(value) {
            val previous = transactions.get()
            transactions.set(value)
            if (value == null && previous != null) closeConnection(previous.connection)
        }

    override fun getConnection(): Connection {
        if (databaseThread?.isCurrent() == true) {
            return kept ?: open().also { kept = it }
        }
        return connections.get() ?: open().also(connections::set)
    }

    override fun closeConnection(connection: Connection) {
        if (databaseThread?.isCurrent() == true) {
            check(kept === connection) { "Connections must be closed on the thread that opened them" }
            if (transaction == null) {
                lastUse = System.nanoTime()
                scheduleIdleClose(idleCloseMs)
            }
            return
        }
        check(connections.get() === connection) { "Connections must be closed on the thread that opened them" }
        if (transaction == null) {
            connection.close()
            connections.remove()
        }
    }

    private fun scheduleIdleClose(delayMs: Long) {
        if (closeScheduled) return
        closeScheduled = try {
            databaseThread!!.executor.schedule(::closeIfIdle, delayMs, TimeUnit.MILLISECONDS)
            true
        } catch (_: RejectedExecutionException) {
            // The thread is shutting down: close now, as JdbcSqliteDriver would have
            closeKept()
            false
        }
    }

    /** On the database thread: closes its connection if no transaction is open and it is idle. */
    private fun closeIfIdle() {
        closeScheduled = false
        val connection = kept ?: return
        if (transaction != null) return scheduleIdleClose(idleCloseMs)
        val idle = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastUse)
        if (idle < idleCloseMs) return scheduleIdleClose(idleCloseMs - idle)
        closeKept()
    }

    /** On the database thread: closes its statements and connection. */
    private fun closeKept() {
        statements.values.forEach { runCatching { it.close() } }
        statements.clear()
        val connection = kept ?: return
        kept = null
        connection.close()
    }

    /**
     * Closes the database thread's connection, on that thread, once no transaction is open there.
     * `JdbcSqliteDriver` closes nothing here, as its connections close after each use. A later
     * use opens a new connection.
     */
    override fun close() {
        val thread = databaseThread ?: return
        if (thread.isCurrent()) {
            if (transaction == null) closeKept()
        } else {
            runCatching { thread.executor.submit(::close).get(5, TimeUnit.SECONDS) }
        }
    }

    /**
     * On the database thread, runs [sql] with a prepared statement kept from an earlier run, as
     * Android's SQLite does; elsewhere as `JdbcSqliteDriver` does, preparing it each time.
     */
    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (databaseThread?.isCurrent() != true) return super.execute(identifier, sql, parameters, binders)
        return QueryResult.Value(
            withStatement(sql) { statement ->
                JdbcPreparedStatement(statement).apply { binders?.invoke(this) }.execute()
            },
        )
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if (databaseThread?.isCurrent() != true) return super.executeQuery(identifier, sql, mapper, parameters, binders)
        return withStatement(sql) { statement ->
            JdbcPreparedStatement(statement).apply { binders?.invoke(this) }
            // Closing the result set resets the statement, so it holds no read transaction open
            statement.executeQuery().use { mapper(JdbcCursor(it)) }
        }
    }

    /**
     * Runs [block] with [sql]'s cached statement, or a new one, on the database thread's connection,
     * then keeps it for the next run. A statement that fails is closed instead. Taken out of the
     * cache while it runs, so a query run from inside [block] prepares its own.
     */
    private inline fun <T> withStatement(sql: String, block: (PreparedStatement) -> T): T {
        val (connection, onClose) = connectionAndClose()
        try {
            val statement = statements.remove(sql) ?: connection.prepareStatement(sql)
            val result = try {
                block(statement).also { statement.clearParameters() }
            } catch (e: Throwable) {
                runCatching { statement.close() }
                throw e
            }
            statements.put(sql, statement)?.let { runCatching { it.close() } }
            return result
        } finally {
            onClose()
        }
    }

    override fun Connection.beginTransaction() {
        prepareStatement("BEGIN TRANSACTION").use { it.execute() }
    }

    override fun Connection.endTransaction() {
        prepareStatement("END TRANSACTION").use { it.execute() }
    }

    override fun Connection.rollbackTransaction() {
        prepareStatement("ROLLBACK TRANSACTION").use { it.execute() }
    }

    /** How many statements the database thread keeps; call on that thread. For tests. */
    internal fun cachedStatements(): Int = statements.size

    /** How many connections this driver has opened, for tests. */
    internal val opened = AtomicInteger()

    private fun open(): Connection = DriverManager.getConnection(url, properties).also { opened.incrementAndGet() }

    companion object {
        /** Distinct statements kept per connection; the app has a few hundred queries in all. */
        private const val MAX_STATEMENTS = 256

        /** How often [deferNotifications] tells watched queries about changes. */
        const val NOTIFY_INTERVAL_MS = 2_000L

        /**
         * Like SQLDelight's `JdbcSqliteDriver(url, properties, schema)`: creates [schema] in a new
         * database, or migrates an older one, by `PRAGMA user_version`.
         */
        fun withSchema(
            url: String,
            properties: Properties,
            databaseThread: DatabaseThread?,
            schema: SqlSchema<QueryResult.Value<Unit>>,
            idleCloseMs: Long = 1_000,
            vararg callbacks: AfterVersion,
        ): DesktopSqliteDriver {
            val driver = DesktopSqliteDriver(url, properties, databaseThread, idleCloseMs)
            object : TransacterImpl(driver) {}.transaction {
                val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
                    QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
                }, 0).value ?: 0L
                if (version == 0L) {
                    schema.create(driver)
                    driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
                } else if (version < schema.version) {
                    schema.migrate(driver, version, schema.version, *callbacks)
                    driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
                }
            }
            return driver
        }
    }
}
