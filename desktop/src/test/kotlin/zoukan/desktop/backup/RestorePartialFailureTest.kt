package zoukan.desktop.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.restore.BackupContentRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.SavedSearchRestorer
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** A restore reports a failing section and still restores everything else. */
class RestorePartialFailureTest {

    private fun mangaRestorer() = mockk<MangaRestorer> {
        coEvery { sortByNew(any()) } answers { firstArg() }
        coEvery { restore(any(), any()) } just Runs
    }

    private val entries = listOf("A", "B", "C").map { BackupManga(source = 1L, url = "/$it", title = it) }

    @Test
    fun `a failing section is reported and every entry is still restored`() {
        val mangaRestorer = mangaRestorer()
        val restorer = BackupContentRestorer(
            categoriesRestorer = mockk(relaxed = true),
            preferenceRestorer = mockk(relaxed = true),
            extensionStoreRestorer = mockk(relaxed = true),
            mangaRestorer = mangaRestorer,
            savedSearchRestorer = mockk<SavedSearchRestorer> {
                coEvery { restoreSavedSearches(any()) } throws IllegalStateException("database is locked")
            },
            feedRestorer = mockk(relaxed = true),
        )
        val backup = Backup(
            backupManga = entries,
            backupSavedSearches = listOf(BackupSavedSearch(name = "s", source = 1L)),
        )

        val errors = runBlocking { restorer.restore(backup, RestoreOptions()) }

        coVerify(exactly = 3) { mangaRestorer.restore(any(), any()) }
        errors.map { it.second }.any { "database is locked" in it } shouldBe true
    }

    @Test
    fun `a failing category section does not stop the restore`() {
        val mangaRestorer = mangaRestorer()
        val restorer = BackupContentRestorer(
            categoriesRestorer = mockk<CategoriesRestorer> {
                coEvery { this@mockk.invoke(any()) } throws IllegalStateException("category insert failed")
            },
            preferenceRestorer = mockk(relaxed = true),
            extensionStoreRestorer = mockk(relaxed = true),
            mangaRestorer = mangaRestorer,
            savedSearchRestorer = mockk(relaxed = true),
            feedRestorer = mockk(relaxed = true),
        )
        val backup = Backup(
            backupManga = entries,
            backupCategories = listOf(BackupCategory(name = "c", order = 0)),
        )

        val errors = runBlocking { restorer.restore(backup, RestoreOptions()) }

        coVerify(exactly = 3) { mangaRestorer.restore(any(), any()) }
        errors.map { it.second }.any { "category insert failed" in it } shouldBe true
    }
}
