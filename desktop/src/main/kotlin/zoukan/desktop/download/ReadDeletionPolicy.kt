package zoukan.desktop.download

import tachiyomi.domain.chapter.model.Chapter

/**
 * After a chapter was read to the end: which read chapter's download "Automatically delete after
 * reading" deletes when the reader closes, and which unread chapters with the same number "Mark
 * duplicate read chapters as read" marks, as Android's ReaderViewModel.
 */
object ReadDeletionPolicy {
    data class Decision(val toDelete: List<Chapter>, val markRead: List<Chapter>)

    /**
     * @param read the chapter just read to the end, with read = true
     * @param navigable the reader's chapter list without skipped chapters, in reading order
     * @param chapters every chapter of the entry with this session's read state
     * @param slots removeAfterReadSlots: -1 off, 0 the chapter just read, n the chapter n back
     */
    fun afterRead(read: Chapter, navigable: List<Chapter>, chapters: List<Chapter>, slots: Int, markDuplicates: Boolean): Decision {
        val toDelete = LinkedHashMap<Long, Chapter>()
        if (slots >= 0) {
            val position = navigable.indexOfFirst { it.id == read.id }
            // The read state of this session, not the one the list was made with
            val chosen = navigable.getOrNull(position - slots)
                ?.let { c -> if (c.id == read.id) read else chapters.find { it.id == c.id } ?: c }
            if (chosen != null && chosen.read) toDelete[chosen.id] = chosen
        }
        var markRead = emptyList<Chapter>()
        if (markDuplicates && read.isRecognizedNumber) {
            val duplicates = chapters.filter { !it.read && it.isRecognizedNumber && it.chapterNumber == read.chapterNumber }
            markRead = duplicates
            // Android deletes them too when "Last read chapter" is the delete setting
            if (slots == 0) duplicates.forEach { toDelete[it.id] = it.copy(read = true) }
        }
        return Decision(toDelete.values.toList(), markRead)
    }
}
