package zoukan.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import zoukan.desktop.ui.browse.ExtensionDetailsScreen
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.ui.browse.MigrateMangaScreen
import zoukan.desktop.ui.browse.OpenUrlScreen
import zoukan.desktop.ui.browse.SourceFeedScreen
import zoukan.desktop.ui.browse.SourceScreen
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.manga.MetadataViewScreen
import zoukan.desktop.ui.manga.PagePreviewScreen
import zoukan.desktop.ui.manga.SearchPurpose
import zoukan.desktop.ui.migration.MigrationConfigScreen
import zoukan.desktop.ui.migration.MigrationListScreen
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.desktop.ui.recs.CommonRecommendsScreen
import zoukan.desktop.ui.recs.RecommendsScreen
import zoukan.desktop.ui.settings.RestoreBackupScreen
import zoukan.desktop.ui.settings.SourceSettingsScreen
import java.lang.reflect.Modifier

/** The app's Screen classes found in its compiled classes, and ways to make each one without a database. */
internal object ScreenScan {
    /** Every Screen class of the app, found in its compiled classes. */
    fun screenClasses(): List<Class<out Screen>> = UiInventory.screenClasses()

    /**
     * How to get a screen of [type] without arguments: an object's instance, or a new one of a
     * class without arguments; null for a class that needs arguments. Nothing is made yet.
     */
    fun maker(type: Class<out Screen>): (() -> Screen)? {
        runCatching { type.getDeclaredField("INSTANCE") }.getOrNull()
            ?.takeIf { Modifier.isStatic(it.modifiers) }
            ?.let { field -> return { field.apply { isAccessible = true }.get(null) as Screen } }
        return type.declaredConstructors.firstOrNull { it.parameterCount == 0 }
            ?.let { constructor -> { constructor.apply { isAccessible = true }.newInstance() as Screen } }
    }

    /**
     * Argument variants for the key and serialisation tests: IDs 1, 12 and 123, and strings where
     * one is a prefix of another. Needs no database, unlike ScreenSmokeTest's makers.
     */
    fun variants(): Map<Class<out Screen>, List<() -> Screen>> {
        val ids = listOf(1L, 12L, 123L)
        return mapOf(
            MangaScreen::class.java to ids.map { id -> { MangaScreen(id) } },
            MetadataViewScreen::class.java to ids.map { id -> { MetadataViewScreen(id, id) } },
            PagePreviewScreen::class.java to ids.map { id -> { PagePreviewScreen(id) } },
            ReaderScreen::class.java to ids.flatMap { id ->
                listOf({ ReaderScreen(id, id) }, { ReaderScreen(id, id * 10 + 2, page = 1) })
            },
            SourceScreen::class.java to ids.flatMap { id ->
                listOf(
                    { SourceScreen(id) },
                    { SourceScreen(id, savedSearch = id) },
                    { SourceScreen(id, follows = true) },
                    { SourceScreen(id, query = "one") },
                )
            },
            GlobalSearchScreen::class.java to listOf(
                { GlobalSearchScreen("one") },
                { GlobalSearchScreen("one piece") },
                { GlobalSearchScreen("one", SearchPurpose.Merge(1)) },
            ),
            SourceFeedScreen::class.java to ids.map { id -> { SourceFeedScreen(id) } },
            MigrateMangaScreen::class.java to ids.map { id -> { MigrateMangaScreen(id) } },
            ExtensionDetailsScreen::class.java to listOf(
                { ExtensionDetailsScreen("a.b") },
                { ExtensionDetailsScreen("a.bc") },
            ),
            OpenUrlScreen::class.java to listOf(
                { OpenUrlScreen("https://x.test/1") },
                { OpenUrlScreen("https://x.test/12") },
            ),
            SourceSettingsScreen::class.java to ids.map { id -> { SourceSettingsScreen(id) } },
            RestoreBackupScreen::class.java to listOf(
                { RestoreBackupScreen("/backups/a.tachibk") },
                { RestoreBackupScreen("/backups/a.tachibk.old") },
            ),
            MigrationListScreen::class.java to listOf(
                { MigrationListScreen(listOf(1), null) },
                { MigrationListScreen(listOf(1, 2), null) },
                { MigrationListScreen(listOf(1), "one") },
                { MigrationListScreen(listOf(12), "one piece") },
            ),
            MigrationConfigScreen::class.java to listOf(
                { MigrationConfigScreen(listOf(1)) },
                { MigrationConfigScreen(listOf(12)) },
                { MigrationConfigScreen(listOf(1, 2)) },
            ),
            RecommendsScreen::class.java to ids.map { id -> { RecommendsScreen(id) } },
            CommonRecommendsScreen::class.java to listOf(
                { CommonRecommendsScreen(listOf(1)) },
                { CommonRecommendsScreen(listOf(1, 12)) },
            ),
        )
    }
}
