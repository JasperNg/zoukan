package zoukan.desktop.ui.settings

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.settings.MissingExtensionsInstall.State
import zoukan.extension.ExtensionException
import zoukan.extension.LoadResult

/** A second restore's missing extensions are found even while the first restore's install runs. */
class MissingExtensionsInstallTest {

    private val store = ExtensionStore(
        indexUrl = "https://a.example/index.min.json",
        name = "A",
        badgeLabel = "A",
        signingKey = "key",
        contact = ExtensionStore.Contact(website = "https://a.example", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private fun available(pkg: String, sourceId: Long) = Extension.Available(
        name = pkg,
        pkgName = pkg,
        versionName = "1.4.1",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        signatureHash = store.signingKey,
        storeName = store.name,
        sources = listOf(Extension.Available.Source(sourceId, "en", "Source $sourceId", "https://s.example")),
        apkUrl = "https://example/$pkg.apk",
        iconUrl = "https://example/$pkg.png",
        store = store,
    )

    private fun plan(pkg: String, sourceId: Long) = MissingExtensionsPlan.of(
        listOf(MissingSource(sourceId, "Source $sourceId")),
        listOf(available(pkg, sourceId)),
        emptyMap(),
        emptyMap(),
    )

    private val originalPlanner = MissingExtensionsInstall.planner

    @AfterEach
    fun tearDown() {
        MissingExtensionsInstall.planner = originalPlanner
        MissingExtensionsInstall.installer = null
        MissingExtensionsInstall.dismiss()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out; state ${MissingExtensionsInstall.state.value}" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `a find during another restore's install runs when the install ends`() {
        val restoreA = Any()
        val restoreB = Any()
        var nextPlan = plan("ext.a", 1)
        MissingExtensionsInstall.planner = { nextPlan }
        val installGate = CompletableDeferred<Unit>()
        MissingExtensionsInstall.installer = { ext ->
            installGate.await()
            LoadResult.Error(ext.pkgName, ExtensionException(ExtensionException.Stage.SIGNATURE, "test"))
        }

        MissingExtensionsInstall.find(restoreA)
        waitUntil { MissingExtensionsInstall.state.value.forRestore(restoreA) is State.Ready }
        MissingExtensionsInstall.install(restoreA)
        MissingExtensionsInstall.state.value.forRestore(restoreA).shouldBeInstanceOf<State.Installing>()

        // Restore B ends while A's install runs: B never shows A's progress, and its find waits
        nextPlan = plan("ext.b", 2)
        MissingExtensionsInstall.dismiss()
        MissingExtensionsInstall.find(restoreB)
        MissingExtensionsInstall.state.value.forRestore(restoreB) shouldBe State.Finding

        installGate.complete(Unit)
        waitUntil { MissingExtensionsInstall.state.value.forRestore(restoreB) is State.Ready }
        val ready = MissingExtensionsInstall.state.value.forRestore(restoreB) as State.Ready
        ready.plan.toInstall.single().extension.pkgName shouldBe "ext.b"
        // A's results are no longer shown anywhere
        MissingExtensionsInstall.state.value.forRestore(restoreA) shouldBe State.Idle
    }

    @Test
    fun `the Settings screen shows a running install while its find waits`() {
        val restore = Any()
        val settings = Any()
        MissingExtensionsInstall.planner = { plan("ext.a", 1) }
        val installGate = CompletableDeferred<Unit>()
        MissingExtensionsInstall.installer = { ext ->
            installGate.await()
            LoadResult.Error(ext.pkgName, ExtensionException(ExtensionException.Stage.SIGNATURE, "test"))
        }

        MissingExtensionsInstall.find(restore)
        waitUntil { MissingExtensionsInstall.state.value.forRestore(restore) is State.Ready }
        MissingExtensionsInstall.install(restore)

        // The user leaves the restore screen and opens Settings > Install missing extensions
        MissingExtensionsInstall.dismiss()
        MissingExtensionsInstall.find(settings)
        MissingExtensionsInstall.state.value.forSettings(settings).shouldBeInstanceOf<State.Installing>()
        // A restore screen still never shows another run's progress
        MissingExtensionsInstall.state.value.forRestore(settings) shouldBe State.Finding

        installGate.complete(Unit)
        waitUntil { MissingExtensionsInstall.state.value.forSettings(settings) is State.Ready }
    }
}
