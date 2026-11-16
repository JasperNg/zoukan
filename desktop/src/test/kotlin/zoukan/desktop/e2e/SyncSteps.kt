package zoukan.desktop.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.tray.Notifications
import zoukan.desktop.ui.AppScope
import java.util.Collections

/**
 * What the two sync journeys share: what a sync tells the user (the notifications the desktop
 * shows as tray messages), the Settings > Data steps, and the backup another device would leave
 * on the remote.
 */
@OptIn(ExperimentalTestApi::class)
internal class SyncSteps(private val journey: Journey) {

    /** Every notification the app posted since this was made: a sync's result is one of them. */
    val notices: MutableList<Notifications.Message> = Collections.synchronizedList(mutableListOf())

    init {
        // A shared flow keeps nothing for a late collector: collect before the first sync
        AppScope.launch { Notifications.flow.collect { notices += it } }
        Thread.sleep(200)
    }

    /** The titles in the library, as the Library tab lists them. */
    fun favoriteTitles(): List<String> = runBlocking { Injekt.get<GetFavorites>().await().map { it.title } }

    /** Marks the chapter numbered [number] of [mangaId] read, as reading it does. */
    fun markRead(mangaId: Long, number: Int) = runBlocking {
        val chapter = Injekt.get<GetChaptersByMangaId>().await(mangaId).first { it.chapterNumber.toInt() == number }
        Injekt.get<ChapterRepository>().update(ChapterUpdate(id = chapter.id, read = true))
    }

    /** The chapter numbers of [mangaId] that are read. */
    fun readChapters(mangaId: Long): List<Int> = runBlocking {
        Injekt.get<GetChaptersByMangaId>().await(mangaId).filter { it.read }.map { it.chapterNumber.toInt() }.sorted()
    }

    /**
     * What a backup of another device that read chapter 2 of [existing] and added [added] looks
     * like: [remote] (what the app uploaded), with the changes at a newer version, so that the
     * app's merge takes them.
     */
    fun otherDevicesBackup(remote: ByteArray, existingTitle: String, added: BackupManga): ByteArray {
        val backup = BackupCodec.decode(remote)
        val changed = backup.backupManga.map { manga ->
            if (manga.title != existingTitle) return@map manga
            manga.version += 10
            manga.chapters.first { it.chapterNumber.toInt() == 2 }.apply {
                read = true
                version += 10
            }
            manga
        }
        return BackupCodec.encode(backup.copy(backupManga = changed + added))
    }

    /** A library entry of the fixture [source]'s [key] title with its one chapter, as another device has it. */
    fun backedUpTitle(source: FixtureSource, key: String): BackupManga {
        val manga = FixtureCatalog.manga(key)!!
        return BackupManga(
            source = source.id,
            url = "/manga/$key",
            title = manga.title,
            favorite = true,
            dateAdded = System.currentTimeMillis(),
            chapters = manga.chapters.mapIndexed { index, chapter ->
                BackupChapter(
                    url = "/chapter/$key/${chapter.key}",
                    name = chapter.name,
                    chapterNumber = chapter.number,
                    sourceOrder = (manga.chapters.size - 1 - index).toLong(),
                    version = 1,
                )
            },
            version = 1,
        )
    }

    /** Opens the row [title] on the Settings page and waits for the text field of its dialog. */
    fun ComposeUiTest.editText(title: String, value: String) {
        scrollSettingsTo(title)
        onAllNodesWithText(title).onFirst().performClick()
        // The page's own search field has a text action too: the dialog's is the last one
        with(journey) { waitFor(hasText("OK")) }
        onAllNodes(hasSetTextAction()).onLast().performTextReplacement(value)
        onAllNodesWithText("OK").onFirst().performClick()
        waitForIdle()
    }

    /** Opens the checkbox dialog of the row [title], ticks or unticks [label] and confirms with OK. */
    fun ComposeUiTest.toggleInDialog(title: String, label: String, checkboxes: Int, index: Int) {
        scrollSettingsTo(title)
        onAllNodesWithText(title).onFirst().performClick()
        with(journey) { waitFor(hasText(label)) }
        // Only the checkbox toggles, not its label. The dialog's checkboxes are the last [checkboxes] toggleables.
        val boxes = onAllNodes(isToggleable())
        val all = boxes.fetchSemanticsNodes().size
        boxes[all - checkboxes + index].performClick()
        onAllNodesWithText("OK").onFirst().performClick()
        waitForIdle()
    }

    /** Picks [label] in the choice dialog of the row [title]. */
    fun ComposeUiTest.choose(title: String, label: String) {
        scrollSettingsTo(title)
        onAllNodesWithText(title).onFirst().performClick()
        with(journey) { waitFor(hasText(label)) }
        onAllNodesWithText(label).onFirst().performClick()
        waitForIdle()
    }

    /** Scrolls the settings page until the row [title] is composed. */
    fun ComposeUiTest.scrollSettingsTo(title: String) {
        // A row can be composed just below the window, where a click lands nowhere: scroll it into view
        val lists = onAllNodes(hasScrollToNodeAction())
        val count = lists.fetchSemanticsNodes().size
        for (i in 0 until count) {
            if (runCatching { lists[i].performScrollToNode(hasText(title)) }.isSuccess) {
                waitForIdle()
                return
            }
        }
        with(journey) { waitFor(hasText(title), timeout = 5_000) }
    }

    /** Presses Sync now and waits for the sync's own notification; returns it. */
    fun ComposeUiTest.syncNow(): Notifications.Message {
        val before = notices.size
        scrollSettingsTo("Sync now")
        onAllNodesWithText("Sync now").onFirst().performClick()
        with(journey) {
            waitOrDump("for the sync to finish with a notification", timeout = 60_000) { notices.size > before }
        }
        waitForIdle()
        return notices[before]
    }
}
