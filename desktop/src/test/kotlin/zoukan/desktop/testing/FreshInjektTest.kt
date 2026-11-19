package zoukan.desktop.testing

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtensionContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import java.io.File

class FreshInjektTest {

    private class Sentinel(val name: String)

    @Test
    fun `FreshInjekt isolates Injekt mutations and restores the previous scope`() {
        val outer = Injekt
        val ext = FreshInjekt()
        val ctx = io.mockk.mockk<ExtensionContext>(relaxed = true)
        ext.beforeEach(ctx)
        try {
            Injekt.addSingleton(Sentinel("scoped"))
            Injekt.get<Sentinel>().name shouldBe "scoped"
        } finally {
            ext.afterEach(ctx)
        }
        (Injekt === outer) shouldBe true
        runCatching { Injekt.get<Sentinel>() }.isFailure shouldBe true
    }

    @Test
    fun `test classes that mutate global Injekt use FreshInjekt and do not register in init`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val injektMutators = listOf(
            "desktop/src/test/kotlin/zoukan/desktop/ui/manga/EditMangaDialogSaveTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/ui/manga/EntryActionsDialogsTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/compat/ContextStubsTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/network/DohClientsTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/sync/SyncManagerTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/AnilistApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/MangaDexFollowApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/KavitaApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/SuwayomiApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/BangumiApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/ShikimoriApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/MyAnimeListApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/MangaUpdatesApiTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/track/KitsuApiTest.kt",
        )
        for (path in injektMutators) {
            val text = File(root, path).readText()
            text shouldContain "FreshInjekt()"
            text shouldNotContain "TrackerTestEnvironment().close"
        }
        val recordedHttp = File(root, "desktop/src/test/kotlin/zoukan/desktop/track/RecordedHttp.kt").readText()
        recordedHttp shouldNotContain "Injekt = InjektScope"

        for (path in listOf(
            "desktop/src/test/kotlin/zoukan/desktop/library/LibraryUpdaterDownloadsTest.kt",
            "desktop/src/test/kotlin/zoukan/desktop/library/LibraryUpdaterRestoreTest.kt",
        )) {
            val text = File(root, path).readText()
            text shouldContain "@TempDir"
            text shouldNotContain "Files.createTempDirectory"
        }
    }
}
