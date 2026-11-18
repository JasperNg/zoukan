package zoukan.desktop.download

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File

/**
 * What the clean-up preview listed is checked again when the user confirms: a chapter read state,
 * a library change or a new entry since the preview keeps the file, and every entry of a shared
 * folder is locked while its files go.
 */
class DownloadCleanupRecheckTest {

    @TempDir
    lateinit var temp: File

    private val kits = mutableListOf<DownloadKit>()

    private fun kit() = DownloadKit(temp).also { kits += it }

    @AfterEach
    fun waitForSaves() = kits.forEach { it.awaitBackground() }

    /** An entry whose library state the test changes. */
    private fun entry(id: Long, title: String, favorite: () -> Boolean): Manga = mockk(relaxed = true) {
        every { this@mockk.id } returns id
        every { source } returns 1L
        every { this@mockk.title } returns title
        every { ogTitle } returns title
        every { this@mockk.favorite } answers { favorite() }
    }

    @Test
    fun `a chapter marked unread since the preview is kept`() = runBlocking<Unit> {
        val kit = kit()
        val manga = kit.manga(1, "Entry")
        var chapters = listOf(kit.chapter(1, 1, read = true))
        val file = kit.write("A (EN)/Entry/Chapter 1.cbz")
        val plan = kit.manager.planCleanup(true, false, { listOf(manga) }, { chapters })
        plan.others.map { it.file } shouldBe listOf(file)

        chapters = listOf(kit.chapter(1, 1, read = false))
        val result = kit.manager.applyCleanupResult(plan)

        file.exists() shouldBe true
        result.deleted shouldBe 0
        result.skipped shouldBe 1
    }

    @Test
    fun `an entry added back to the library since the preview keeps its chapters`() = runBlocking<Unit> {
        val kit = kit()
        var favorite = false
        val manga = entry(1, "Entry") { favorite }
        val chapters = listOf(kit.chapter(1, 1, read = false))
        val file = kit.write("A (EN)/Entry/Chapter 1.cbz")
        val plan = kit.manager.planCleanup(false, true, { listOf(manga) }, { chapters })
        plan.others.single().reason shouldBe DownloadManager.CleanupReason.NOT_IN_LIBRARY

        favorite = true
        kit.manager.applyCleanup(plan) shouldBe 0

        file.exists() shouldBe true
    }

    @Test
    fun `a ticked orphaned folder that has an entry now is kept`() = runBlocking<Unit> {
        val kit = kit()
        val owner = kit.manga(1, "Entry")
        val folder = kit.write("A (EN)/Nobody/Chapter 1.cbz").parentFile
        kit.write("A (EN)/Entry/Chapter 1.cbz")
        var mangas = listOf(owner)
        val plan = kit.manager.planCleanup(true, true, { mangas }, { emptyList<Chapter>() })
        plan.orphans.map { it.file } shouldBe listOf(folder)

        mangas = listOf(owner, kit.manga(2, "Nobody"))
        val result = kit.manager.applyCleanupResult(plan, orphansToDelete = setOf(folder))

        folder.isDirectory shouldBe true
        result.skipped shouldBe 1
    }

    @Test
    fun `a shared folder waits for every owner's lock, not only the first`() = runBlocking<Unit> {
        val kit = kit()
        val one = kit.manga(1, "Same")
        val two = kit.manga(2, "Same.")
        val chapters = mapOf(1L to listOf(kit.chapter(1, 1, read = true)), 2L to listOf(kit.chapter(2, 2)))
        val file = kit.write("A (EN)/Same/Chapter 1.cbz")
        val plan = kit.manager.planCleanup(true, false, { listOf(one, two) }, { chapters[it].orEmpty() })
        plan.others.map { it.file } shouldBe listOf(file)

        // Entry 2's download is writing into the shared folder; its lock says when the clean-up asks for it
        val lock = Mutex()
        val asked = CompletableDeferred<Unit>()
        kit.manager.entryLocks[2] = object : Mutex by lock {
            override suspend fun lock(owner: Any?) {
                asked.complete(Unit)
                lock.lock(owner)
            }
        }
        lock.lock()
        val applying = async(Dispatchers.IO) { kit.manager.applyCleanup(plan) }
        // The owners' locks are taken in id order: holding entry 1's, the clean-up asks for entry 2's,
        // which this test holds, so nothing can be deleted yet
        withTimeout(10_000) { asked.await() }
        kit.manager.entryLock(1).isLocked shouldBe true
        applying.isCompleted shouldBe false
        file.exists() shouldBe true
        lock.unlock()

        applying.await() shouldBe 2 // the chapter, then the emptied folder
        file.exists() shouldBe false
    }
}
