package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.download.DownloadPaths
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import kotlin.time.Duration.Companion.minutes

/**
 * Merges an entry of one fixture source with a title of a second one: More > Merge with another
 * entry, searching for the other title, picking it on the second source and confirming. The merged
 * entry lists the chapters of both (a chapter number both have shows once), Edit merged entries
 * takes the second source out of the merge and its chapters leave the list, and a second source
 * that answers 500 leaves the first one's chapters on screen with an error message. A chapter of
 * the second source reads across the merge, and downloads through it into the second source's
 * folder. The Merged entries dialog's Updates and Downloads boxes are saved on the merge's
 * reference, and a refresh skips the source whose updates are off.
 *
 *   ./gradlew :desktop:journeyTest --tests "*MergeEntriesJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class MergeEntriesJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun mergeAnEntryWithATitleOfASecondSource() {
        journey = Journey.start("merge")
        val mirror = journey.addSource()
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null

        // "Fixture Second" has one chapter; "Fixture Formats" on the second source has four, of which
        // chapter 1 is a number both have
        val second = journey.addToLibrary("Fixture Second")
        val pickedTitle = FixtureCatalog.FORMATS_TITLE
        val onlyInTheMirror = listOf("Chapter 2 (PNG)", "Chapter 3 (WEBP)", "Chapter 4 (AVIF)")

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 15.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()

                step("Open Fixture Second: it has its own chapter only") {
                    runOnUiThread { navigator?.push(MangaScreen(second.id)) }
                    waitFor(hasText("Chapter 1"))
                    onAllNodesWithText(onlyInTheMirror[0]).fetchSemanticsNodes().size shouldBe 0
                    shot("1-entry")
                }

                step("More > Merge with another entry: search for $pickedTitle, pick it on ${mirror.name}") {
                    onAllNodesWithContentDescription("More").onFirst().performClick()
                    onAllNodesWithText("Merge with another entry").onFirst().performClick()
                    waitFor(hasText(mirror.name))
                    // The search starts with the entry's own title; ask for the other one
                    onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(pickedTitle)
                    onAllNodesWithContentDescription("Search").onFirst().performClick()
                    waitFor(hasText("Merge with: $pickedTitle"))
                    // Both fixture sources list the title, each under its own name: the mirror's row is the second
                    waitOrDump("for the title on both sources") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 2 }
                    shot("2-pick")
                    onAllNodesWithTag("manga-item")[1].performClick()
                    waitFor(hasText("Merge with $pickedTitle?"))
                    onAllNodesWithText("Merge").onFirst().performClick()
                }

                step("The merged entry lists both sources' chapters, each source fetched once") {
                    onlyInTheMirror.forEach { waitFor(hasText(it)) }
                    waitFor(hasText("Chapter 1"))
                    val merged = runBlocking { Injekt.get<GetManga>().await(second.id) }
                    println("Journey $name: the entry after the merge: ${merged?.title}")
                    // A source's chapter list is asked at <entry>/chapters, so count every path under the entry
                    server.requests.count { it == "/manga/second" || it.startsWith("/manga/second/") } shouldBeGreaterThan 0
                    server.requests.count { it == "/manga/formats" || it.startsWith("/manga/formats/") } shouldBeGreaterThan 0
                    shot("3-merged")
                }

                step("A chapter only ${mirror.name} has reads across the merge") {
                    onAllNodesWithText(onlyInTheMirror[0]).onFirst().performClick()
                    waitOrDump("for page 1 of ${onlyInTheMirror[0]} to draw") { pageDrew(FixtureCatalog.pages("png")[0]) }
                    shot("4-reader")
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasText(onlyInTheMirror[0]))
                }

                step("${mirror.name} answers 500: the other source's chapter stays and an error shows") {
                    server.fail("/manga/formats", FixtureFailure.Status(500))
                    onAllNodesWithContentDescription("Refresh").onFirst().performClick()
                    waitOrDump("for the error message") { onAllNodes(hasText("500", substring = true)).fetchSemanticsNodes().isNotEmpty() }
                    waitFor(hasText("Chapter 1"))
                    shot("5-error")
                    server.clearFailures()
                }

                val mergedId = runBlocking { Injekt.get<GetManga>().await(second.url, exh.source.MERGED_SOURCE_ID)!!.id }
                val mirrorEntry = runBlocking { Injekt.get<GetManga>().await("/manga/formats", mirror.id)!! }
                fun mirrorReference() = runBlocking { Injekt.get<GetMergedReferencesById>().await(mergedId) }.single { it.mangaSourceId == mirror.id }

                step("A chapter only ${mirror.name} has downloads through the merge into ${mirror.name}'s folder") {
                    val downloads = Injekt.get<DownloadManager>()
                    val paths = Injekt.get<DownloadPaths>()
                    val chapter = runBlocking { Injekt.get<GetChaptersByMangaId>().await(mirrorEntry.id) }.single { it.name == onlyInTheMirror[0] }
                    onAllNodesWithText(onlyInTheMirror[0]).onFirst().performMouseInput { rightClick() }
                    waitFor(hasText("Download"))
                    onAllNodesWithText("Download").onFirst().performClick()
                    waitOrDump("for the chapter to download", 120_000) {
                        downloads.queue.value.isEmpty() && !downloads.running.value && downloads.findChapter(mirrorEntry, chapter) != null
                    }
                    val file = downloads.findChapter(mirrorEntry, chapter)!!.canonicalFile
                    // Under the mirror's source folder, in the folder of the mirror's own entry
                    file.startsWith(paths.mangaDir(mirrorEntry).canonicalFile) shouldBe true
                    file.startsWith(paths.sourceDir(source.id).canonicalFile) shouldBe false
                    // The merged entry shows it downloaded
                    waitFor(hasContentDescription("Downloaded"))
                    shot("6-downloaded-through-merge")
                }

                step("Edit merged entries: the Updates and Downloads boxes are saved on the reference, and a refresh skips a source without updates") {
                    mirrorReference().getChapterUpdates shouldBe true
                    mirrorReference().downloadChapters shouldBe true
                    onAllNodesWithContentDescription("More").onFirst().performClick()
                    onAllNodesWithText("Edit merged entries").onFirst().performClick()
                    waitFor(hasText("Merged entries"))
                    waitOrDump("for both entries") { onAllNodes(hasContentDescription("Remove from the merge")).fetchSemanticsNodes().size == 2 }
                    val row = dialogRow(mergedId, mirror.id)
                    onAllNodesWithText("Updates").fetchSemanticsNodes().size shouldBe 2
                    onAllNodesWithText("Updates")[row].performClick()
                    waitOrDump("for ${mirror.name}'s updates to be off", 10_000) { !mirrorReference().getChapterUpdates }
                    onAllNodesWithText("Downloads")[row].performClick()
                    waitOrDump("for ${mirror.name}'s downloads to be off", 10_000) { !mirrorReference().downloadChapters }
                    // The other entry keeps both
                    runBlocking { Injekt.get<GetMergedReferencesById>().await(mergedId) }
                        .filter { it.mangaSourceId == source.id }
                        .forEach {
                            it.getChapterUpdates shouldBe true
                            it.downloadChapters shouldBe true
                        }
                    shot("7-dialog-boxes")
                    onAllNodesWithText("Close").onFirst().performClick()

                    // A refresh asks the journey's source for its chapters, and not the mirror
                    fun asked(key: String) = server.requests.count { it == "/manga/$key/chapters" }
                    val second0 = asked("second")
                    val formats0 = asked("formats")
                    onAllNodesWithContentDescription("Refresh").onFirst().performClick()
                    waitOrDump("for the refresh to ask the journey's source") { asked("second") > second0 }
                    waitFor(hasContentDescription("Refresh") and isEnabled())
                    asked("formats") shouldBe formats0
                    // Its chapters stay in the list
                    waitFor(hasText(onlyInTheMirror[0]))

                    // Back on, the mirror's chapters are asked for again
                    onAllNodesWithContentDescription("More").onFirst().performClick()
                    onAllNodesWithText("Edit merged entries").onFirst().performClick()
                    waitOrDump("for both entries") { onAllNodes(hasContentDescription("Remove from the merge")).fetchSemanticsNodes().size == 2 }
                    onAllNodesWithText("Updates")[dialogRow(mergedId, mirror.id)].performClick()
                    waitOrDump("for ${mirror.name}'s updates to be on", 10_000) { mirrorReference().getChapterUpdates }
                    onAllNodesWithText("Close").onFirst().performClick()
                    onAllNodesWithContentDescription("Refresh").onFirst().performClick()
                    waitOrDump("for the refresh to ask ${mirror.name}") { asked("formats") > formats0 }
                }

                step("Edit merged entries: take ${mirror.name} out of the merge, its chapters leave") {
                    val references = runBlocking { Injekt.get<GetMergedReferencesById>().await(mergedId) }
                        .filter { it.mangaSourceId != exh.source.MERGED_SOURCE_ID }
                    val mirrorRow = dialogRow(mergedId, mirror.id)
                    println("Journey $name: the dialog lists ${references.map { it.mangaSourceId }}, ${mirror.name} is row $mirrorRow")
                    references.size shouldBe 2
                    onAllNodesWithContentDescription("More").onFirst().performClick()
                    onAllNodesWithText("Edit merged entries").onFirst().performClick()
                    waitFor(hasText("Merged entries"))
                    shot("8-dialog")
                    onAllNodes(hasContentDescription("Remove from the merge")).fetchSemanticsNodes().size shouldBe 2
                    onAllNodes(hasContentDescription("Remove from the merge"))[mirrorRow].performClick()
                    waitOrDump("for one entry left in the merge") {
                        onAllNodes(hasContentDescription("Remove from the merge")).fetchSemanticsNodes().size == 1
                    }
                    onAllNodesWithText("Close").onFirst().performClick()
                    waitOrDump("for ${mirror.name}'s chapters to leave the list") {
                        onlyInTheMirror.all { onAllNodesWithText(it).fetchSemanticsNodes().isEmpty() }
                    }
                    waitFor(hasText("Chapter 1"))
                    shot("9-after-removal")
                }
            }
        }
    }

    /** The row of [sourceId]'s entry in the Merged entries dialog, which lists the merge's references in order. */
    private fun dialogRow(mergedId: Long, sourceId: Long): Int = runBlocking { Injekt.get<GetMergedReferencesById>().await(mergedId) }
        .filter { it.mangaSourceId != exh.source.MERGED_SOURCE_ID }
        .indexOfFirst { it.mangaSourceId == sourceId }
}
