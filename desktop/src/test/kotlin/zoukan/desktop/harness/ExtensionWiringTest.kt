package zoukan.desktop.harness

import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import mihon.data.extension.service.ExtensionStoreService
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.ZoukanRuntime
import zoukan.desktop.secrets.SecretStore
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.DesktopExtensionLoader
import java.io.File
import java.nio.file.Files

/**
 * Installs every extension of one store and checks it is wired up on the desktop (see
 * [ExtensionWiringCheck]): its bytecode uses no class, method or field the app lacks, its Injekt
 * lookups resolve, its sources build their client, headers and filters, a request through each
 * source's client arrives with its headers, and its settings save and read back.
 *
 * `zoukan.e2e.store` names the store, one of [SampleStores.ALL] (default
 * keiyoushi). The Gradle task `extensionWiringTest` runs it once per store, each in its own JVM.
 * It downloads every extension, so it runs only when asked; `zoukan.e2e.package` limits it to
 * some extensions (comma-separated, for example en.mangapill,all.mangadex):
 *
 *   ./gradlew :desktop:extensionWiringTest -Pzoukan.e2e.stores=yuzono
 *
 * The report goes to `build/reports/e2e/extensions-wiring-<store>.md`. The test fails on a gap in
 * the app (an API several extensions use that the compatibility layer lacks, a missing Injekt
 * registration), or when the store lists far fewer extensions than it should (it moved, or its
 * index is a stub); problems of single extensions are listed in the report.
 */
class ExtensionWiringTest {

    @Test
    fun `every extension is wired up`() {
        assumeTrue(System.getProperty("zoukan.e2e") == "true", "End-to-end test; run with -Pzoukan.e2e=true")
        val storeId = System.getProperty("zoukan.e2e.store") ?: SampleStores.KEIYOUSHI.id
        val store = SampleStores.ALL.firstOrNull { it.id == storeId }
            ?: error("Unknown store \"$storeId\"; the stores are ${SampleStores.ALL.map { it.id }}")
        val dirs = ZoukanDirs(Files.createTempDirectory("zoukan-e2e-wiring").toFile())
        System.setProperty(SecretStore.FILE_STORE_PROPERTY, "true")
        ZoukanRuntime.start(dirs, interactiveBrowser = false)
        Injekt.get<NetworkHelper>()
        val packages = System.getProperty("zoukan.e2e.package")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
        val check = ExtensionWiringCheck(
            dirs = dirs,
            storeService = Injekt.get<ExtensionStoreService>(),
            installer = Injekt.get<DesktopExtensionInstaller>(),
            loader = Injekt.get<DesktopExtensionLoader>(),
            options = ExtensionWiringCheck.Options(
                storeUrl = store.url,
                storeId = store.id,
                packages = packages.orEmpty(),
                minExtensions = store.minExtensions,
            ),
        )
        val (report, rows) = check.run()
        report.save(File("build/reports/e2e/extensions-wiring-${store.id}.md"))

        rows.flatMap { it.injekt }.distinct().shouldBeEmpty()
        ExtensionWiringCheck.appGaps(rows).shouldBeEmpty()
    }
}
