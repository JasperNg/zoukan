package zoukan.desktop.ui.reader

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ReaderCleanupTest {
    @Test
    fun `the chapter file is closed before the read downloads are deleted`() = runBlocking<Unit> {
        val events = mutableListOf<String>()
        val file = AutoCloseable { events += "close" }
        ReaderCleanup.closeThenDelete(file, listOf(Chapter.create().copy(id = 1L))) { events += "delete ${it.map { c -> c.id }}" }
        events shouldContainExactly listOf("close", "delete [1]")
    }

    @Test
    fun `a file that fails to close does not stop the deletes`() = runBlocking<Unit> {
        val events = mutableListOf<String>()
        val file = AutoCloseable { error("locked") }
        ReaderCleanup.closeThenDelete(file, listOf(Chapter.create())) { events += "delete" }
        events shouldContainExactly listOf("delete")
    }

    @Test
    fun `a failed delete is not thrown`() = runBlocking<Unit> {
        var closed = false
        ReaderCleanup.closeThenDelete(AutoCloseable { closed = true }, listOf(Chapter.create())) { error("in use") }
        closed shouldBe true
    }
}

class KeepsImageTest {
    @Test
    fun `pages near the current one keep their image`() {
        keepsImage(index = 12, center = 10, behind = 4, ahead = 10, visible = IntRange.EMPTY) shouldBe true
        keepsImage(index = 5, center = 10, behind = 4, ahead = 10, visible = IntRange.EMPTY) shouldBe false
    }

    @Test
    fun `visible pages far from the middle one keep their image`() {
        // A zoomed-out webtoon shows pages 0 to 30 with page 15 in the middle
        (0..30).all { keepsImage(it, center = 15, behind = 4, ahead = 10, visible = 0..30) } shouldBe true
        keepsImage(index = 40, center = 15, behind = 4, ahead = 10, visible = 0..30) shouldBe false
    }
}
