package zoukan.desktop.testing.fakes

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import java.sql.SQLException
import java.util.Collections

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
