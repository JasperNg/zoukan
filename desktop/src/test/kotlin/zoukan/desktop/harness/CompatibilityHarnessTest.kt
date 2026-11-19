package zoukan.desktop.harness

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.DOH_PROVIDERS
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.harnessDohProvider
import zoukan.desktop.harnessRuns
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.DesktopExtensionLoader
import zoukan.extension.ExtensionClassLoader
import zoukan.extension.LoadResult
import java.io.File
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

/**
 * The real stores the extension wiring check and the live tests download from. They live in the
 * tests only: the app names no store and ships no store URL. Each [id] must match a store in
 * `extensionWiringStores` in desktop/build.gradle.kts, which runs one test task per store.
 */
object SampleStores {

    /** A store and the fewest extensions it should list (fewer means it moved or was emptied). */
    data class SampleStore(val id: String, val url: String, val minExtensions: Int)

    val KEIYOUSHI = SampleStore(
        id = "keiyoushi",
        url = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json",
        minExtensions = 1000,
    )

    /**
     * yuzono's "Cursed" store: six NSFW extensions with their own signing key. Only the tests use
     * it, so its extensions go through the same checks; the app does not add or trust it
     * (desktop/THREAT-MODEL.md), a user adds it as any other store.
     */
    val YUZONO = SampleStore(
        id = "yuzono",
        url = "https://raw.githubusercontent.com/yuzono/cursed-manga-repo/repo/repo.json",
        minExtensions = 1,
    )

    val ALL = listOf(KEIYOUSHI, YUZONO)
}

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
        val store = testExtensionStore()
        val service = mockStoreService(store, listOf(availableExtension(pkg, store)))
        return service to store
    }
}

class CompatibilityHarnessTimeoutTest {

    @TempDir
    lateinit var temp: File

    /** Never counted down while the test runs: a source stuck in a blocking network read. */
    private val latch = CountDownLatch(1)

    private var loads = 0
    private val blockingLoader = mockk<ExtensionClassLoader>(relaxed = true)
    private val fineLoader = mockk<ExtensionClassLoader>(relaxed = true)

    @AfterEach
    fun release() = latch.countDown()

    private fun installedWith(pkg: String, source: CatalogueSource, classLoader: ExtensionClassLoader? = null): LoadResult.Success {
        val extension = mockk<Extension.Installed>()
        every { extension.pkgName } returns "eu.kanade.tachiyomi.extension.$pkg"
        every { extension.sources } returns listOf(source)
        return LoadResult.Success(extension, classLoader)
    }

    private fun source(): CatalogueSource {
        val source = mockk<CatalogueSource>()
        every { source.name } returns "Test"
        every { source.lang } returns "en"
        return source
    }

    private fun timeoutInstaller(): DesktopExtensionInstaller {
        val installer = mockk<DesktopExtensionInstaller>()
        coEvery { installer.install(any(), any()) } returns mockk<DesktopExtensionInstaller.Installed>(relaxed = true) {
            every { method } returns DesktopExtensionInstaller.Method.JAR
        }
        return installer
    }

    private fun blockingSource(): CatalogueSource = source().also {
        // A blocking, non-suspending wait, as an Rx source in OkHttp's execute()
        coEvery { it.getPopularManga(1) } coAnswers {
            latch.await()
            error("unreachable")
        }
    }

    private fun fineSource(): CatalogueSource = source().also {
        coEvery { it.getPopularManga(1) } returns MangasPage(emptyList(), false)
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a source that blocks gets a timed out row and the run goes on`() {
        val store = testExtensionStore()
        val storeService = mockStoreService(
            store,
            listOf(availableExtension("en.blocking", store), availableExtension("en.fine", store)),
        )
        val blocking = blockingSource()
        val fine = fineSource()
        val loader = mockk<DesktopExtensionLoader>()
        every { loader.load(any(), any()) } answers {
            // Called in the order the extensions run
            if (loads++ == 0) {
                installedWith("en.blocking", blocking, blockingLoader)
            } else {
                installedWith("en.fine", fine, fineLoader)
            }
        }

        val harness = CompatibilityHarness(
            dirs = ZoukanDirs(temp),
            storeService = storeService,
            installer = timeoutInstaller(),
            loader = loader,
            options = CompatibilityHarness.Options(storeUrl = "https://example.org/repo.json", packages = listOf("en.blocking", "en.fine")),
            stepTimeout = 1.seconds,
        )
        val (_, rows) = harness.run()

        rows.map { it.pkg } shouldBe listOf("en.blocking", "en.fine")
        rows[0].error.lowercase() shouldContain "timed out"
        rows[1].error shouldBe "popular: empty"
    }

    /** Runs one extension: the blocking one (stuck in the latch) or the fine one, with a short step timeout. */
    private fun runOnly(pkg: String) {
        val store = testExtensionStore()
        val storeService = mockStoreService(store, listOf(availableExtension(pkg, store)))
        val blocking = blockingSource()
        val fine = fineSource()
        val loader = mockk<DesktopExtensionLoader>()
        every { loader.load(any(), any()) } answers {
            if (pkg == "en.blocking") {
                installedWith(pkg, blocking, blockingLoader)
            } else {
                installedWith(pkg, fine, fineLoader)
            }
        }
        CompatibilityHarness(
            dirs = ZoukanDirs(temp),
            storeService = storeService,
            installer = timeoutInstaller(),
            loader = loader,
            options = CompatibilityHarness.Options(storeUrl = "https://example.org/repo.json", packages = listOf(pkg)),
            stepTimeout = 1.seconds,
        ).run()
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `the class loader of every loaded extension is closed`() {
        runOnly("en.fine")
        verify(exactly = 1) { fineLoader.close() }
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `the class loader of an extension that timed out is closed`() {
        runOnly("en.blocking")
        // The worker is still blocked in the latch (released in AfterEach)
        verify(exactly = 1) { blockingLoader.close() }
    }
}

class WiringGapKindTest {

    private fun kind(symbol: String, reason: String) = ExtensionWiringCheck.kindOf(ApiLinkage.Gap(symbol, reason))

    @Test
    fun `a bundled copy of a library the app also has is known`() {
        kind("nl.adaptivity.xmlutil.XmlReader", ApiLinkage.SHADOWED) shouldBe ExtensionWiringCheck.GapKind.KNOWN
        kind("org.chromium.support_lib_boundary.WebViewProviderBoundaryInterface", ApiLinkage.SHADOWED) shouldBe
            ExtensionWiringCheck.GapKind.KNOWN
    }

    @Test
    fun `a member the app's copy of a bundled library lacks is still a missing API`() {
        // The app's older copy runs, so a call to a newer method fails with NoSuchMethodError
        kind("nl.adaptivity.xmlutil.XmlReader.newMethod()V", "missing method") shouldBe ExtensionWiringCheck.GapKind.APP
        kind("org.chromium.support_lib_boundary.NewInterface", "missing class") shouldBe ExtensionWiringCheck.GapKind.APP
    }

    @Test
    fun `an Android-only library is known whatever the gap`() {
        kind("me.zhanghai.android.libarchive.Archive.readNew()J", "missing method") shouldBe ExtensionWiringCheck.GapKind.KNOWN
    }
}

class WiringProbeTest {

    @Test
    fun `a probe an interceptor sends to an outside host is blocked and noted`() {
        val looked = CopyOnWriteArrayList<String>()
        // Stands in for the system resolver: records the lookup and fails, so nothing leaves the machine
        val recordingDns = Dns { host ->
            looked += host
            throw UnknownHostException(host)
        }
        // A source client whose interceptor sends every request to the site's own host
        val sourceClient = OkHttpClient.Builder()
            .dns(recordingDns)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url("https://manga.example/api").build()) }
            .build()
        val blocked = CopyOnWriteArrayList<String>()
        runCatching {
            ExtensionWiringCheck.probeClient(sourceClient, blocked)
                .newCall(Request.Builder().url("http://127.0.0.1:9/wiring/1").build()).execute().close()
        }
        looked.shouldBeEmpty()
        blocked shouldContainExactly listOf("manga.example")
    }
}

private fun testExtensionStore(): ExtensionStore {
    val store = mockk<ExtensionStore>()
    every { store.name } returns "Test store"
    every { store.signingKey } returns "0123456789abcdef0123456789abcdef"
    return store
}

private fun availableExtension(pkg: String, store: ExtensionStore) = Extension.Available(
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

private fun mockStoreService(store: ExtensionStore, extensions: List<Extension.Available>): ExtensionStoreService {
    val service = mockk<ExtensionStoreService>()
    coEvery { service.fetch(any()) } returns Result.success(store)
    coEvery { service.getExtensions(store) } returns Result.success(extensions)
    return service
}
