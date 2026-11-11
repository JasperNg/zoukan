package zoukan.desktop.library

import tachiyomi.domain.libraryUpdateError.interactor.DeleteLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.interactor.InsertLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.model.LibraryUpdateError
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.InsertLibraryUpdateErrorMessages
import tachiyomi.domain.libraryUpdateErrorMessage.model.LibraryUpdateErrorMessage

/**
 * The library update's errors in the database, as the Android LibraryUpdateJob keeps them (KMK): one
 * row per entry that failed its last update, with the error message. The update errors screen
 * lists them grouped by message.
 */
class LibraryUpdateErrors(
    private val deleteErrors: DeleteLibraryUpdateErrors,
    private val insertErrors: InsertLibraryUpdateErrors,
    private val insertMessages: InsertLibraryUpdateErrorMessages,
) {
    /** Drops the errors of entries that left the library. */
    suspend fun clean() {
        deleteErrors.cleanUnrelevantMangaErrors()
    }

    /** The entry updated: its error, if any, is gone. */
    suspend fun clear(mangaId: Long) {
        deleteErrors.deleteMangaError(listOf(mangaId))
    }

    /** The entry failed to update with [message]; replaces its earlier error. */
    suspend fun record(mangaId: Long, message: String) {
        val messageId = insertMessages.insert(LibraryUpdateErrorMessage(-1L, message))
        insertErrors.upsert(LibraryUpdateError(id = -1L, mangaId = mangaId, messageId = messageId))
    }

    /** Removes the errors with these ids, without updating their entries. */
    suspend fun delete(errorIds: List<Long>) {
        deleteErrors.delete(errorIds)
    }
}
