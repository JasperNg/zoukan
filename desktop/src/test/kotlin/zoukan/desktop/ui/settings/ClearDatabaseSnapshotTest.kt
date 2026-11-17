package zoukan.desktop.ui.settings

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.GetSourceIdsWithNonLibraryManga
import zoukan.desktop.backup.SafetySnapshot
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Clear database saves a safety backup before it deletes, and deletes nothing when the user cancels after one fails. */
class ClearDatabaseSnapshotTest {
    // The model's scope runs on Main; a thread of its own stands in for the UI thread
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val models = mutableListOf<ClearDatabaseScreenModel>()

    @TempDir
    lateinit var dir: File

    init {
        Dispatchers.setMain(main)
    }

    @AfterEach
    fun tearDown() {
        models.forEach { it.onDispose() }
        Dispatchers.resetMain()
        main.close()
    }

    private val deleted = CountDownLatch(1)

    private fun handler(): DatabaseHandler = mockk {
        every { subscribeToList<GetSourceIdsWithNonLibraryManga>(any()) } returns flowOf(listOf(GetSourceIdsWithNonLibraryManga(1L, 3L)))
        coEvery { await<Any>(any(), any()) } answers {
            deleted.countDown()
            Unit
        }
    }

    private fun model(handler: DatabaseHandler, fail: Boolean, prompt: (String) -> Boolean) =
        ClearDatabaseScreenModel(
            handler = handler,
            sourceManager = mockk(relaxed = true),
            snapshot = SafetySnapshot(folder = { dir }, write = { file ->
                if (fail) throw IOException("disk full")
                file.writeText("before")
            }),
            snapshotPrompt = { prompt(it) },
        ).also { models += it }

    private fun selectAll(model: ClearDatabaseScreenModel) = runBlocking {
        withTimeout(5_000) { model.state.first { !it.items.isNullOrEmpty() } }
        model.selectAll()
    }

    @Test
    fun `a safety backup is saved before the entries are deleted`() {
        val handler = handler()
        val model = model(handler, fail = false, prompt = { error("must not ask") })
        selectAll(model)

        model.delete(keepReadManga = true)

        deleted.await(5, TimeUnit.SECONDS) shouldBe true
        dir.listFiles()!!.single().name shouldStartWith "safety_clear-database_"
    }

    @Test
    fun `nothing is deleted when the backup fails and the user cancels`() {
        val handler = handler()
        val asked = CountDownLatch(1)
        val model = model(handler, fail = true, prompt = {
            asked.countDown()
            false
        })
        selectAll(model)

        model.delete(keepReadManga = true)

        asked.await(5, TimeUnit.SECONDS) shouldBe true
        deleted.await(500, TimeUnit.MILLISECONDS) shouldBe false
        coVerify(exactly = 0) { handler.await<Any>(any(), any()) }
    }

    @Test
    fun `the user may clear without a backup when it fails`() {
        val handler = handler()
        val model = model(handler, fail = true, prompt = { true })
        selectAll(model)

        model.delete(keepReadManga = true)

        deleted.await(5, TimeUnit.SECONDS) shouldBe true
    }
}
