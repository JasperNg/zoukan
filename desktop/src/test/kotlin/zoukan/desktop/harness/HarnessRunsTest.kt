package zoukan.desktop.harness

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.DOH_PROVIDERS
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
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

/** The harness runs once per store URL, with its own report name; the app itself names no store. */
class HarnessRunsTest {

    @TempDir
    lateinit var temp: File

    private val url = "https://example.org/repo.json"

    @Test
    fun `with no --store the harness refuses to run, since the app names no store`() {
        assertThrows<IllegalArgumentException> { harnessRuns(CompatibilityHarness.Options(), emptyList()) }
            .message!! shouldContain "--store <url>"
    }

    @Test
    fun `a store name is a usage error, only URLs are taken`() {
        assertThrows<IllegalArgumentException> { harnessRuns(CompatibilityHarness.Options(), listOf("some-store")) }
            .message!! shouldContain "give its URL"
    }

    @Test
    fun `one --store keeps a missing package a failure and gets a name from its GitHub owner`() {
        val run = harnessRuns(CompatibilityHarness.Options(), listOf("https://raw.githubusercontent.com/some-one/repo/repo/repo.json")).single()
        run.storeId shouldBe "some-one"
        run.skipMissing shouldBe false
        run.minExtensions shouldBe 0
    }

    @Test
    fun `stores of one owner get distinct report names, so their reports never overwrite each other`() {
        val runs = harnessRuns(
            CompatibilityHarness.Options(packages = listOf("en.mangapill")),
            listOf(
                "https://raw.githubusercontent.com/some-one/a/repo/repo.json",
                "https://raw.githubusercontent.com/some-one/b/repo/repo.json",
                "https://raw.githubusercontent.com/some-one/a/repo/repo.json",
                "https://example.org/x/repo.json",
            ),
        )
        runs.map { it.storeId } shouldContainExactly listOf("some-one", "some-one-2", "example.org")
        runs.all { it.skipMissing } shouldBe true
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
    fun `a harness without a store URL fails before it fetches anything`() {
        val (service, _) = storeWith("en.a")
        val harness = CompatibilityHarness(
            dirs = ZoukanDirs(temp),
            storeService = service,
            installer = mockk<DesktopExtensionInstaller>(),
            loader = mockk<DesktopExtensionLoader>(),
            options = CompatibilityHarness.Options(packages = listOf("en.a"), offline = true),
        )
        assertThrows<IllegalArgumentException> { harness.run() }
        coVerify(exactly = 0) { service.fetch(any()) }
    }

    @Test
    fun `every test store has a wiring test task, and the other way round`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val tasks = Regex("""val extensionWiringStores = listOf\(([^)]*)\)""")
            .find(File(root, "desktop/build.gradle.kts").readText())!!.groupValues[1]
            .split(',').map { it.trim().removeSurrounding("\"") }
        tasks shouldContainExactly SampleStores.ALL.map { it.id }
        SampleStores.ALL.map { it.url }.distinct().size shouldBe SampleStores.ALL.size
    }

    @Test
    fun `a store that lists too few extensions fails the run`() {
        val (service, _) = storeWith("en.a")
        val harness = CompatibilityHarness(
            dirs = ZoukanDirs(temp),
            storeService = service,
            installer = mockk<DesktopExtensionInstaller>(),
            loader = mockk<DesktopExtensionLoader>(),
            options = CompatibilityHarness.Options(storeUrl = url, packages = listOf("en.a"), offline = true, minExtensions = 1000),
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
            options = CompatibilityHarness.Options(storeUrl = url, packages = listOf("en.elsewhere"), offline = true, skipMissing = true),
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
            options = CompatibilityHarness.Options(storeUrl = url, packages = listOf("en.elsewhere"), offline = true),
        )
        harness.run().second.single().error shouldBe "not in store"
    }

    @Test
    fun `extension wiring task excludes testStandins and enables file secrets`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val build = File(root, "desktop/build.gradle.kts").readText()
        val wiringBlock = build.substringAfter("val extensionWiringStoreTests =").substringBefore("extensionWiringStoreTests.zipWithNext")
        build shouldContain "testStandins"
        wiringBlock shouldContain "testStandins.output"
        wiringBlock shouldContain """systemProperty("zoukan.fileSecrets", "true")"""

        val wiringTest = File(root, "desktop/src/test/kotlin/zoukan/desktop/harness/ExtensionWiringTest.kt").readText()
        val filePropIdx = wiringTest.indexOf("SecretStore.FILE_STORE_PROPERTY")
        val startIdx = wiringTest.indexOf("ZoukanRuntime.start(")
        (filePropIdx in 0 until startIdx) shouldBe true
        val optionsBlock = wiringTest.substringAfter("ExtensionWiringCheck.Options(")
        optionsBlock shouldNotContain "exclude"
        optionsBlock shouldNotContain "all.mangadex"
        optionsBlock shouldNotContain "all.komga"

        for (rel in listOf(
            "eu/kanade/tachiyomi/extension/all/mangadex/MangaDex.kt",
            "eu/kanade/tachiyomi/extension/all/komga/Komga.kt",
        )) {
            File(root, "desktop/src/testStandins/kotlin/$rel").isFile shouldBe true
            File(root, "desktop/src/test/kotlin/$rel").exists() shouldBe false
        }
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
