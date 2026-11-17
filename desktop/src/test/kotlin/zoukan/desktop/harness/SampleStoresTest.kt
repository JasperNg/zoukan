package zoukan.desktop.harness

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.DOH_PROVIDERS
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.harnessDohProvider
import zoukan.desktop.harnessRuns
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.DesktopExtensionLoader
import java.io.File

/** The harness and the wiring check run per store: each sample store with its sample, report name and drift guard. */
class SampleStoresTest {

    @TempDir
    lateinit var temp: File

    @Test
    fun `with no --store the harness runs both sample stores, skipping packages a store does not list`() {
        val runs = harnessRuns(CompatibilityHarness.Options(packages = listOf("en.mangapill")), emptyList())
        runs.map { it.storeId } shouldContainExactly listOf("keiyoushi", "yuzono")
        runs.map { it.storeUrl } shouldContainExactly CompatibilityHarness.SAMPLE_STORES.map { it.url }
        runs.all { it.skipMissing } shouldBe true
        runs.map { it.minExtensions } shouldContainExactly listOf(1000, 1)
    }

    @Test
    fun `--doh names map to the settings' providers, and an unknown name is refused`() {
        harnessDohProvider("quad9") shouldBe PREF_DOH_QUAD9
        harnessDohProvider(" Quad9 ") shouldBe PREF_DOH_QUAD9
        harnessDohProvider("off") shouldBe -1
        harnessDohProvider("quad101") shouldBe null
        // Every provider the settings offer has a name
        DOH_PROVIDERS.forEach { provider ->
            listOf("cloudflare", "google", "adguard", "quad9", "alidns", "dnspod", "360", "mullvad", "controld", "njalla")
                .map(::harnessDohProvider) shouldContain provider
        }
    }

    @Test
    fun `one --store keeps a missing package a failure`() {
        val run = harnessRuns(CompatibilityHarness.Options(), listOf("yuzono")).single()
        run.storeUrl shouldBe CompatibilityHarness.YUZONO.url
        run.skipMissing shouldBe false
    }

    @Test
    fun `a store URL gets a name from its GitHub owner and no drift guard`() {
        val run = harnessRuns(CompatibilityHarness.Options(), listOf("https://raw.githubusercontent.com/some-one/repo/repo/repo.json")).single()
        run.storeId shouldBe "some-one"
        run.minExtensions shouldBe 0
    }

    @Test
    fun `an unknown store name is a usage error`() {
        assertThrows<IllegalArgumentException> { harnessRuns(CompatibilityHarness.Options(), listOf("nope")) }
    }

    @Test
    fun `the Keiyoushi sample is the default sample plus ten, and the yuzono sample is its six extensions`() {
        CompatibilityHarness.KEIYOUSHI.packages.size shouldBe 30
        CompatibilityHarness.KEIYOUSHI.packages.take(20) shouldContainExactly CompatibilityHarness.DEFAULT_PACKAGES
        CompatibilityHarness.YUZONO.packages.size shouldBe 6
    }

    @Test
    fun `a store that lists too few extensions fails the run`() {
        val (service, _) = storeWith("en.a")
        val harness = CompatibilityHarness(
            dirs = ZoukanDirs(temp),
            storeService = service,
            installer = mockk<DesktopExtensionInstaller>(),
            loader = mockk<DesktopExtensionLoader>(),
            options = CompatibilityHarness.Options(packages = listOf("en.a"), offline = true, minExtensions = 1000),
        )
        assertThrows<IllegalStateException> { harness.run() }.message!! shouldContain "lists 1 extensions, expected at least 1000"
    }

    @Test
    fun `with skipMissing a package the store does not list gets no row`() {
        val (service, _) = storeWith("en.a")
        val harness = CompatibilityHarness(
            dirs = ZoukanDirs(temp),
            storeService = service,
            installer = mockk<DesktopExtensionInstaller>(),
            loader = mockk<DesktopExtensionLoader>(),
            options = CompatibilityHarness.Options(packages = listOf("en.elsewhere"), offline = true, skipMissing = true),
        )
        harness.run().second shouldBe emptyList()
    }

    @Test
    fun `without skipMissing a package the store does not list is a failed row`() {
        val (service, _) = storeWith("en.a")
        val harness = CompatibilityHarness(
            dirs = ZoukanDirs(temp),
            storeService = service,
            installer = mockk<DesktopExtensionInstaller>(),
            loader = mockk<DesktopExtensionLoader>(),
            options = CompatibilityHarness.Options(packages = listOf("en.elsewhere"), offline = true),
        )
        harness.run().second.single().error shouldBe "not in store"
    }

    private fun storeWith(pkg: String): Pair<ExtensionStoreService, ExtensionStore> {
        val store = mockk<ExtensionStore>()
        every { store.name } returns "Test store"
        every { store.signingKey } returns "0123456789abcdef0123456789abcdef"
        val extension = Extension.Available(
            name = pkg,
            pkgName = "eu.kanade.tachiyomi.extension.$pkg",
            versionName = "1.0.0",
            versionCode = 1,
            libVersion = 1.4,
            lang = "en",
            isNsfw = false,
            signatureHash = "",
            storeName = "test",
            sources = emptyList(),
            apkUrl = "",
            iconUrl = "",
            store = store,
        )
        val service = mockk<ExtensionStoreService>()
        coEvery { service.fetch(any()) } returns Result.success(store)
        coEvery { service.getExtensions(store) } returns Result.success(listOf(extension))
        return service to store
    }
}
