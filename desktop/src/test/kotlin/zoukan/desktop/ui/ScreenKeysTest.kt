package zoukan.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.ui.browse.SourceFeedScreen
import zoukan.desktop.ui.browse.SourceScreen
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.desktop.ui.recs.RecommendsScreen
import zoukan.desktop.ui.settings.SourceSettingsScreen

/**
 * Voyager disposes every screen model whose key starts with the key of a screen that left. So no
 * screen's key may start with another's, or leaving one screen cancels the work of another that
 * is still shown (leaving manga 1 stopped manga 12's screen).
 */
class ScreenKeysTest {

    @Test
    fun `no screen key starts with another`() {
        val screens: List<Screen> = listOf(1L, 12L, 123L).flatMap { id ->
            listOf(
                MangaScreen(id),
                ReaderScreen(id, id),
                ReaderScreen(id, id * 10 + 2),
                RecommendsScreen(id),
                SourceFeedScreen(id),
                SourceSettingsScreen(id),
                SourceScreen(id),
                SourceScreen(id, savedSearch = id),
                SourceScreen(id, follows = true),
            )
        } + listOf(GlobalSearchScreen("one"), GlobalSearchScreen("one piece")) +
            // ZOUKAN -->
            listOf(
                zoukan.desktop.ui.browse.SourcesFilterScreen,
                zoukan.desktop.ui.browse.SourceCategoryScreen,
                zoukan.desktop.ui.settings.ExtensionStoresScreen,
                zoukan.desktop.ui.settings.ClearDatabaseScreen,
                zoukan.desktop.ui.settings.CreateBackupScreen,
                zoukan.desktop.ui.settings.RestoreBackupScreen("/backups/a.tachibk"),
                zoukan.desktop.ui.settings.RestoreBackupScreen("/backups/a.tachibk.old"),
            )
        // ZOUKAN <--
        val clashes = screens.flatMap { a ->
            screens.filter { b -> a !== b && a.key != b.key && b.key.startsWith(a.key) }.map { b -> "${a.key} -> ${b.key}" }
        }
        clashes.shouldBeEmpty()
    }
}
