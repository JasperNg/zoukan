package zoukan.desktop.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.browse.BatchAddScreen
import zoukan.desktop.ui.browse.ExtensionDetailsScreen
import zoukan.desktop.ui.browse.GlobalSearchScreen
import zoukan.desktop.ui.browse.MigrateMangaScreen
import zoukan.desktop.ui.browse.OpenUrlScreen
import zoukan.desktop.ui.browse.SourceCategoryScreen
import zoukan.desktop.ui.browse.SourceFeedScreen
import zoukan.desktop.ui.browse.SourceScreen
import zoukan.desktop.ui.browse.SourcesFilterScreen
import zoukan.desktop.ui.category.CategoryScreen
import zoukan.desktop.ui.downloads.DownloadsScreen
import zoukan.desktop.ui.library.SortTagScreen
import zoukan.desktop.ui.library.UpdateErrorsScreen
import zoukan.desktop.ui.manga.MangaScreen
import zoukan.desktop.ui.manga.MetadataViewScreen
import zoukan.desktop.ui.manga.PagePreviewScreen
import zoukan.desktop.ui.manga.SearchPurpose
import zoukan.desktop.ui.migration.MigrationConfigScreen
import zoukan.desktop.ui.migration.MigrationListScreen
import zoukan.desktop.ui.more.SettingsScreen
import zoukan.desktop.ui.reader.ReaderScreen
import zoukan.desktop.ui.recs.CommonRecommendsScreen
import zoukan.desktop.ui.recs.RecommendsScreen
import zoukan.desktop.ui.settings.ClearDatabaseScreen
import zoukan.desktop.ui.settings.CreateBackupScreen
import zoukan.desktop.ui.settings.ExtensionStoresScreen
import zoukan.desktop.ui.settings.RestoreBackupScreen
import zoukan.desktop.ui.settings.SourceSettingsScreen
import zoukan.desktop.ui.stats.StatsScreen
import zoukan.desktop.ui.upcoming.UpcomingScreen
import zoukan.desktop.ui.whatsnew.WhatsNewScreen
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
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

/**
 * Voyager disposes every screen model whose key starts with the key of a screen that left. So no
 * screen's key may start with another's, or leaving one screen cancels the work of another that
 * is still shown (leaving manga 1 stopped manga 12's screen).
 */
class ScreenKeysTest {

    @Test
    fun `every screen class has prefix-free keys`() {
        val variants = ScreenScan.variants()
        val classes = ScreenScan.screenClasses().filter { it != HomeScreen::class.java }
        // Every screen that takes arguments has variants here; a new one fails and is named
        classes.filter { ScreenScan.maker(it) == null }.filter { it !in variants }.map { it.name }.shouldBeEmpty()
        val screens: List<Screen> = classes.flatMap { type ->
            variants[type]?.map { it() } ?: listOfNotNull(ScreenScan.maker(type)?.invoke())
        }
        val clashes = screens.flatMap { a ->
            screens.filter { b -> a !== b && a.key != b.key && b.key.startsWith(a.key) }.map { b -> "${a.key} -> ${b.key}" }
        }
        clashes.shouldBeEmpty()
    }

    /**
     * A key that ends with a terminator ("/", or ")" where the argument is closed in brackets) can't
     * be the start of another screen's key that names a longer ID. Object screens are left out: each
     * has one fixed key, which the prefix-free test checks against every other key.
     */
    @Test
    fun `every screen key with arguments ends with a terminator`() {
        val open = ScreenScan.variants().values.flatten().map { it() }
            .filterNot { it.key.endsWith("/") || it.key.endsWith(")") }
            .map { "${it::class.simpleName}: ${it.key}" }
        open.shouldBeEmpty()
    }
}

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

    @Test
    fun `every screen class round-trips with an equal key`() {
        val variants = ScreenScan.variants()
        val failures = ScreenScan.screenClasses().flatMap { type ->
            val makers = variants[type] ?: listOfNotNull(ScreenScan.maker(type))
            makers.mapNotNull { make ->
                val screen = make()
                val restored = runCatching { screen.roundTrip() as Screen }
                    .getOrElse { return@mapNotNull "${type.simpleName}: ${it::class.simpleName} ${it.message}" }
                when {
                    restored.key != screen.key -> "${type.simpleName}: key ${restored.key} != ${screen.key}"
                    // Objects come back as themselves; screens with arguments come back equal
                    type.declaredConstructors.any { it.parameterCount > 0 } && restored != screen ->
                        "${type.simpleName}: restored copy is not equal"
                    else -> null
                }
            }
        }
        failures.shouldBeEmpty()
    }

    private fun Screen.roundTrip(): Any {
        val bytes = ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(bytes).use { it.writeObject(this) }
            bytes.toByteArray()
        }
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
    }
}

private class Probe : ScreenModel {
    var disposed = false

    override fun onDispose() {
        disposed = true
    }
}

/** Shows a probe model under the key of [real], so the real key decides what Voyager disposes. */
private class KeyOf(val real: Screen, val models: MutableList<Probe>) : Screen {
    override val key: ScreenKey = real.key

    @Composable
    override fun Content() {
        val model = rememberScreenModel { Probe().also { models += it } }
        Text(key + model.hashCode())
    }
}

/** The same screen twice on the stack must not share a model: going back from one cancels only its own. */
@OptIn(ExperimentalTestApi::class)
class ScreenStackDisposalTest {

    @Test
    fun `going back from a second copy keeps the first copy's model`() = runDesktopComposeUiTest {
        var navigator: Navigator? = null
        val first = mutableListOf<Probe>()
        setContent {
            Navigator(KeyOf(MangaScreen(1), first)) {
                navigator = it
                CurrentScreen()
            }
        }
        waitForIdle()
        runOnUiThread { navigator!!.push(KeyOf(MangaScreen(2), mutableListOf())) }
        waitForIdle()
        runOnUiThread { navigator!!.push(KeyOf(MangaScreen(1), mutableListOf())) }
        waitForIdle()
        runOnUiThread { navigator!!.pop() }
        waitForIdle()
        first.single().disposed shouldBe false
    }

    @Test
    fun `going back from a screen disposes its own model`() = runDesktopComposeUiTest {
        var navigator: Navigator? = null
        val upper = mutableListOf<Probe>()
        setContent {
            Navigator(KeyOf(MangaScreen(1), mutableListOf())) {
                navigator = it
                CurrentScreen()
            }
        }
        waitForIdle()
        runOnUiThread { navigator!!.push(KeyOf(MangaScreen(2), upper)) }
        waitForIdle()
        runOnUiThread { navigator!!.pop() }
        waitForIdle()
        upper.single().disposed shouldBe true
    }
}

class HardCodedStringsTest {
    private val root = File("src/main/kotlin/zoukan/desktop")

    /** The screens whose labels must come from the string resources. Other areas add their folders here. */
    private val scanned = listOf(
        "ui/library",
        "ui/browse",
        "ui/category",
        "ui/whatsnew",
        "ui/stats",
        "ui/migration",
        "migration/MigrateManga.kt",
    )

    private val patterns = listOf(
        Regex("""Text\("[^"$]*\p{L}"""),
        Regex("""(confirmLabel|acceptText|dismissText|label) = "[^"$]*\p{L}"""),
        Regex("""-> "[A-Z][a-z]"""),
        Regex(""""\$\{\w+\}[dhm]"|"0m""""),
    )

    /** Symbols and example text that are not words to translate. */
    private val allowed = listOf("https://…", "•", "18+")

    @Test
    fun `library and browse screens take their labels from the string resources`() {
        val hits = scanned.map { File(root, it) }
            .flatMap { f -> if (f.isFile) listOf(f) else f.walkTopDown().filter { it.extension == "kt" }.toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@mapIndexedNotNull null
                    if (patterns.none { it.containsMatchIn(line) } || allowed.any { it in line }) {
                        null
                    } else {
                        "${file.relativeTo(root)}:${i + 1}: $trimmed"
                    }
                }
            }
        hits shouldBe emptyList()
    }
}
