package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.compat.localized
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.download.DownloadManager
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settings.openSettingsCategory
import zoukan.i18n.desktop.DMR
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes

/**
 * Downloads a chapter encrypted, backs out of removing the download password, and checks the
 * password is still there, the file is byte-for-byte what it was, and the chapter still opens.
 *
 *   ./gradlew :desktop:journeyTest --tests "*EncryptedDownloadJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class EncryptedDownloadJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun cancellingRemoveKeepsTheEncryptedDownloadReadable() {
        journey = Journey.start("encrypted-download")
        val preferences = Injekt.get<DesktopPreferences>()
        val downloads = Injekt.get<DownloadManager>()
        val cbzPassword = Injekt.get<CbzPassword>()
        cbzPassword.set("fixture-pass")
        Injekt.get<SecurityPreferences>().passwordProtectDownloads().set(true)
        var navigator: Navigator? = null
        val manga = journey.addToLibrary(FixtureCatalog.FORMATS_TITLE)
        val format = FixtureCatalog.formats.first()
        fun chapters(): List<Chapter> = runBlocking { Injekt.get<GetChaptersByMangaId>().await(manga.id) }.sortedBy { it.chapterNumber }
        var before = ByteArray(0)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }
                waitForIdle()
                runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                waitFor(hasText("Start") and isEnabled())

                step("Download one chapter encrypted") {
                    onAllNodes(hasContentDescription("Download")).onFirst().performClick()
                    waitFor(hasText("Next chapter"))
                    onAllNodesWithText("Next chapter").onFirst().performClick()
                    waitOrDump("for the download to finish", timeout = 120_000) {
                        downloads.queue.value.isEmpty() && !downloads.running.value &&
                            downloads.downloadedIds(manga, chapters()).size == 1
                    }
                    val file = downloads.findChapter(manga, chapters()[0]).shouldNotBeNull()
                    before = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                }

                step("Cancel Remove in the download password dialog") {
                    openSettingsCategory(SettingsCategory.Downloads)
                    runOnUiThread { HomeScreen.openSettings(navigator!!) }
                    waitFor(hasText(localized(DMR.strings.change_the_download_password)))
                    onAllNodesWithText(localized(DMR.strings.change_the_download_password)).onFirst().performClick()
                    waitFor(hasText(localized(MR.strings.action_remove)))
                    onAllNodesWithText(localized(MR.strings.action_remove)).onFirst().performClick()
                    waitFor(hasText(localized(DMR.strings.download_password_change_warning), substring = true))
                    cbzPassword.get()?.concatToString() shouldBe "fixture-pass"
                    // Cancel of the confirmation, the dialog opened last
                    onAllNodesWithText(localized(MR.strings.action_cancel)).onLast().performClick()
                    waitForIdle()
                    cbzPassword.get()?.concatToString() shouldBe "fixture-pass"
                }

                step("The chapter still opens and its file is unchanged") {
                    cbzPassword.get()?.concatToString() shouldBe "fixture-pass"
                    val file = downloads.findChapter(manga, chapters()[0]).shouldNotBeNull()
                    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).contentEquals(before) shouldBe true
                    runOnUiThread { navigator?.popUntilRoot() }
                    runOnUiThread { navigator?.push(MangaScreen(manga.id)) }
                    waitFor(hasText("Start") and isEnabled())
                    onAllNodesWithText("Chapter 1 (${format.uppercase()})").onFirst().performClick()
                    readThrough(FixtureCatalog.pages(format), "encrypted-reopen")
                }
            }
        }
    }
}
