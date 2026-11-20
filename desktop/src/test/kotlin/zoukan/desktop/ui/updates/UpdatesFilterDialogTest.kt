package zoukan.desktop.ui.updates

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_filter_bookmarked
import tachiyomi.i18n.action_filter_unread
import tachiyomi.i18n.label_downloaded
import tachiyomi.i18n.label_started
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.hide_excluded_scanlators
import java.io.File

/** The Updates filter dialog: each row drives its own preference, and only that one. */
@OptIn(ExperimentalTestApi::class)
class UpdatesFilterDialogTest {

    @TempDir
    lateinit var root: File

    private val property = "$CONFIG_PREFIX.server.rootDir"
    private var previousRoot: String? = null

    @BeforeEach
    fun setRoot() {
        previousRoot = System.getProperty(property)
        System.setProperty(property, root.absolutePath)
    }

    @AfterEach
    fun restoreRoot() {
        previousRoot?.let { System.setProperty(property, it) } ?: System.clearProperty(property)
    }

    /** A real store (the in-memory one forgets a value between two calls), in the temp folder */
    private fun preferences() = UpdatesPreferences(DesktopPreferenceStore(JavaSharedPreferences("updates_filter_${System.nanoTime()}")))

    private fun UpdatesPreferences.all() = listOf(
        filterDownloaded().get(),
        filterUnread().get(),
        filterStarted().get(),
        filterBookmarked().get(),
    )

    @Test
    fun `each filter row sets its own preference`() = runDesktopComposeUiTest {
        val preferences = preferences()
        setContent { UpdatesFilterDialog(preferences, onDismiss = {}) }
        val off = TriState.DISABLED
        val on = TriState.ENABLED_IS

        onNodeWithText(localized(MR.strings.action_filter_unread)).performClick()
        waitForIdle()
        preferences.all() shouldBe listOf(off, on, off, off)

        onNodeWithText(localized(MR.strings.action_filter_bookmarked)).performClick()
        waitForIdle()
        preferences.all() shouldBe listOf(off, on, off, on)

        onNodeWithText(localized(MR.strings.label_downloaded)).performClick()
        waitForIdle()
        preferences.all() shouldBe listOf(on, on, off, on)

        onNodeWithText(localized(MR.strings.label_started)).performClick()
        waitForIdle()
        preferences.all() shouldBe listOf(on, on, on, on)

        // A second click on Unread excludes the unread chapters, and the row says so
        onNodeWithText(localized(MR.strings.action_filter_unread)).performClick()
        waitForIdle()
        preferences.filterUnread().get() shouldBe TriState.ENABLED_NOT
        preferences.filterBookmarked().get() shouldBe on

        onNodeWithText(localized(DMR.strings.hide_excluded_scanlators)).performClick()
        waitForIdle()
        preferences.filterExcludedScanlators().get() shouldBe true
    }
}
