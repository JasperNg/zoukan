package zoukan.desktop.download

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ReadDeletionPolicyTest {

    private fun chapter(id: Long, read: Boolean, manga: Long = 1, number: Double = id.toDouble()) =
        Chapter.create().copy(id = id, mangaId = manga, chapterNumber = number, read = read)

    /** Chapters 1..5, all read, the fifth just read. */
    private val list = (1L..5L).map { chapter(it, read = true) }
    private val justRead get() = list[4]

    private fun decide(
        slots: Int,
        navigable: List<Chapter> = list,
        chapters: List<Chapter> = navigable,
        read: Chapter = justRead,
        duplicates: Boolean = false,
    ) = ReadDeletionPolicy.afterRead(read, navigable, chapters, slots, duplicates)

    @Test
    fun `slot 0 chooses the chapter just read`() {
        decide(0).toDelete.map { it.id } shouldContainExactly listOf(5L)
    }

    @Test
    fun `slot 1 chooses the read chapter before it, slot 2 the one two back`() {
        decide(1).toDelete.map { it.id } shouldContainExactly listOf(4L)
        decide(2).toDelete.map { it.id } shouldContainExactly listOf(3L)
    }

    @Test
    fun `an unread chapter in the slot is never chosen`() {
        val navigable = list.map { if (it.id == 4L) it.copy(read = false) else it }
        decide(1, navigable).toDelete.shouldBeEmpty()
    }

    @Test
    fun `with slots of 1 or more the open chapter is never chosen`() {
        for (slots in 1..3) decide(slots).toDelete.map { it.id } shouldNotContain 5L
    }

    @Test
    fun `slots off chooses nothing`() {
        decide(-1).toDelete.shouldBeEmpty()
    }

    @Test
    fun `a merged entry chapters are chosen from the chapter's own entry`() {
        val navigable = listOf(
            chapter(1, true, manga = 10),
            chapter(2, true, manga = 20),
            chapter(3, true, manga = 10),
        )
        val chosen = decide(1, navigable, read = navigable[2]).toDelete
        chosen.map { it.id } shouldContainExactly listOf(2L)
        chosen.single().mangaId shouldBe 20L
    }

    @Test
    fun `unread duplicates of the chapter number are marked read, and deleted only with slot 0`() {
        val dup = chapter(9, read = false, number = 5.0)
        val chapters = list + dup
        val withSlot1 = decide(1, chapters = chapters, duplicates = true)
        withSlot1.markRead.map { it.id } shouldContainExactly listOf(9L)
        withSlot1.toDelete.map { it.id } shouldNotContain 9L
        val withSlot0 = decide(0, chapters = chapters, duplicates = true)
        withSlot0.markRead.map { it.id } shouldContainExactly listOf(9L)
        withSlot0.toDelete.map { it.id } shouldContainExactly listOf(5L, 9L)
        decide(0, chapters = chapters, duplicates = false).markRead.shouldBeEmpty()
    }

    @Test
    fun `a slot before the first chapter chooses nothing`() {
        decide(5).toDelete.shouldBeEmpty()
    }
}
