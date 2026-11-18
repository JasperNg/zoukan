package tachiyomi.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The transaction rules of [AndroidDatabaseHandler] that the repositories rely on to keep the
 * library whole: nesting, rollback on failure, cancellation, and one transaction at a time.
 * (The desktop's own driver, its connection and its idle close are tested in the desktop module.)
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DatabaseHandlerTransactionTest {

    @TempDir
    lateinit var temp: File

    private lateinit var database: TestDatabase
    private val handler get() = database.handler

    @BeforeEach
    fun open() {
        database = TestDatabase(temp)
    }

    @AfterEach
    fun close() {
        database.close()
    }

    private suspend fun names(): List<String> =
        handler.awaitList { categoriesQueries.getCategories() }.map { it.name }.filter { it.isNotEmpty() }

    private fun Database.addCategory(name: String, order: Long = 1) = categoriesQueries.insert(name, order, 0, 0)

    @Test
    fun `a nested transaction joins the outer one and sees its uncommitted rows`() = runBlocking<Unit> {
        handler.await(inTransaction = true) {
            addCategory("Outer", 1)
            handler.await(inTransaction = true) {
                addCategory("Inner", 2)
            }
            // A plain read inside the transaction runs on its connection, so it sees both
            handler.awaitList { categoriesQueries.getCategories() }.map { it.name } shouldContainExactly
                listOf("", "Outer", "Inner")
        }
        names() shouldBe listOf("Outer", "Inner")
    }

    @Test
    fun `a failure after a nested transaction rolls back the nested rows too`() = runBlocking<Unit> {
        shouldThrow<IllegalStateException> {
            handler.await(inTransaction = true) {
                handler.await(inTransaction = true) { addCategory("Inner") }
                addCategory("Outer")
                error("fails after both writes")
            }
        }
        names() shouldBe emptyList()
    }

    @Test
    fun `a nested failure caught by the outer block still rolls the whole transaction back`() = runBlocking<Unit> {
        // SQLDelight marks the enclosing transaction failed when a nested one fails; a caller
        // cannot keep half of the work by catching the nested error
        handler.await(inTransaction = true) {
            addCategory("Outer")
            runCatching {
                handler.await(inTransaction = true) {
                    addCategory("Inner")
                    error("nested fails")
                }
            }
        }
        names() shouldBe emptyList()
        // And the database goes on working afterwards
        handler.await(inTransaction = true) { addCategory("After") }
        names() shouldBe listOf("After")
    }

    @Test
    fun `cancelling the caller lets a running transaction block finish and commit, then frees the thread`() =
        runBlocking<Unit> {
            // The block runs in a runBlocking of its own on the database thread, so cancelling the
            // caller does not interrupt it: the transaction commits as a whole once the block
            // returns, and only then does the caller see the cancellation. Nothing is half written.
            val written = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val job = launch {
                handler.await(inTransaction = true) {
                    addCategory("Started before the cancel", 1)
                    written.complete(Unit)
                    release.await(30, TimeUnit.SECONDS)
                    addCategory("Finished after the cancel", 2)
                }
            }
            withTimeout(10_000) { written.await() }
            job.cancel()
            release.countDown()
            withTimeout(10_000) { job.join() }
            job.isCancelled shouldBe true
            withTimeout(10_000) {
                handler.await(inTransaction = true) { addCategory("Next", 3) }
                names() shouldBe listOf("Started before the cancel", "Finished after the cancel", "Next")
            }
        }

    @Test
    fun `concurrent read-modify-write transactions run one at a time and lose no update`() = runBlocking<Unit> {
        val count = 25
        coroutineScope {
            (1..count).map { i ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    handler.await(inTransaction = true) {
                        val highest = categoriesQueries.getCategories().executeAsList().maxOf { it.order }
                        addCategory("C$i", highest + 1)
                    }
                }
            }.awaitAll()
        }
        val orders = handler.awaitList { categoriesQueries.getCategories() }.filter { it.name.isNotEmpty() }.map { it.order }
        // The system category sorts at -1, so the first one written gets 0
        orders.sorted() shouldBe (0L until count).toList()
    }

    @Test
    fun `a watched query hears of committed rows and never of rolled-back ones`() = runBlocking<Unit> {
        val seen = mutableListOf<List<String>>()
        val keptSeen = CompletableDeferred<Unit>()
        val watcher = launch {
            handler.subscribeToList { categoriesQueries.getCategories() }.collect { list ->
                val names = list.map { it.name }
                seen += names
                if ("Kept" in names) keptSeen.complete(Unit)
            }
        }
        runCatching {
            handler.await(inTransaction = true) {
                addCategory("Rolled back")
                error("fails")
            }
        }
        handler.await(inTransaction = true) { addCategory("Kept") }
        withTimeout(10_000) { keptSeen.await() }
        watcher.cancelAndJoin()
        seen.none { "Rolled back" in it } shouldBe true
    }

    @Test
    fun `awaitOneOrNull returns null for no row and awaitOne fails instead`() = runBlocking<Unit> {
        handler.awaitOneOrNull { categoriesQueries.getCategory(12345) } shouldBe null
        shouldThrow<NullPointerException> { handler.awaitOne { categoriesQueries.getCategory(12345) } }
        Unit
    }
}
