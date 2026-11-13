package zoukan.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.browse.BatchAddScreen
import zoukan.desktop.ui.browse.SourceCategoryScreen
import zoukan.desktop.ui.browse.SourcesFilterScreen
import zoukan.desktop.ui.category.CategoryScreen
import zoukan.desktop.ui.downloads.DownloadsScreen
import zoukan.desktop.ui.library.SortTagScreen
import zoukan.desktop.ui.library.UpdateErrorsScreen
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.more.SettingsScreen
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.desktop.ui.settings.ClearDatabaseScreen
import zoukan.desktop.ui.settings.CreateBackupScreen
import zoukan.desktop.ui.settings.ExtensionStoresScreen
import zoukan.desktop.ui.stats.StatsScreen
import zoukan.desktop.ui.upcoming.UpcomingScreen
import zoukan.desktop.ui.whatsnew.WhatsNewScreen
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * Komikku's ScreenSerializationTest (app/src/test) for the desktop screens: Voyager screens are
 * java.io.Serializable, so a restored screen must still be the same screen.
 */
class ScreenSerializationTest {

    @Test
    fun `class screen restores as a copy with the same key`() {
        val screen = WhatsNewScreen()

        val restored = screen.roundTrip().shouldBeInstanceOf<WhatsNewScreen>()

        restored.key shouldBe screen.key
    }

    @Test
    fun `data class screens restore equal with the same key`() {
        listOf(MangaScreen(12), ReaderScreen(12, 34, page = 5)).forEach {
            val restored = it.roundTrip()
            restored shouldBe it
            (restored as Screen).key shouldBe it.key
        }
    }

    @Test
    fun `object screens restore as the same instance`() {
        listOf(
            HomeScreen,
            DownloadsScreen,
            SettingsScreen,
            CreateBackupScreen,
            zoukan.desktop.ui.settings.MissingExtensionsScreen,
            ClearDatabaseScreen,
            CategoryScreen,
            StatsScreen,
            UpcomingScreen,
            UpdateErrorsScreen,
            SortTagScreen,
            BatchAddScreen,
            SourcesFilterScreen,
            SourceCategoryScreen,
            ExtensionStoresScreen,
        ).forEach {
            it.roundTrip() shouldBeSameInstanceAs it
        }
    }

    private fun Screen.roundTrip(): Any {
        val bytes = ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(bytes).use { it.writeObject(this) }
            bytes.toByteArray()
        }
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
    }
}
