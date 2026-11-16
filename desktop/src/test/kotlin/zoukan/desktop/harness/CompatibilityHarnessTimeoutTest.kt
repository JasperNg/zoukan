package zoukan.desktop.harness

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.ZoukanDirs
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.DesktopExtensionLoader
import zoukan.extension.ExtensionClassLoader
import zoukan.extension.LoadResult
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

class CompatibilityHarnessTimeoutTest {

    @TempDir
    lateinit var temp: File

    /** Never counted down while the test runs: a source stuck in a blocking network read. */
    private val latch = CountDownLatch(1)

    @AfterEach
    fun release() = latch.countDown()

    private fun available(pkg: String, store: ExtensionStore) = Extension.Available(
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

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a source that blocks gets a timed out row and the run goes on`() {
        val store = mockk<ExtensionStore>()
        every { store.name } returns "Test store"
        every { store.signingKey } returns "0123456789abcdef0123456789abcdef"
        val blocking = available("en.blocking", store)
        val fine = available("en.fine", store)

        val storeService = mockk<ExtensionStoreService>()
        coEvery { storeService.fetch(any()) } returns Result.success(store)
        coEvery { storeService.getExtensions(store) } returns Result.success(listOf(blocking, fine))

        val installer = mockk<DesktopExtensionInstaller>()
        coEvery { installer.install(any(), any()) } returns mockk<DesktopExtensionInstaller.Installed>(relaxed = true) {
            every { method } returns DesktopExtensionInstaller.Method.JAR
        }

        val blockingSource = source().also {
            // A blocking, non-suspending wait, as an Rx source in OkHttp's execute()
            coEvery { it.getPopularManga(1) } coAnswers {
                latch.await()
                error("unreachable")
            }
        }
        val fineSource = source().also { coEvery { it.getPopularManga(1) } returns MangasPage(emptyList(), false) }
        val loader = mockk<DesktopExtensionLoader>()
        every { loader.load(any(), any()) } answers {
            // Called in the order the extensions run
            if (loads++ == 0) {
                installedWith("en.blocking", blockingSource, blockingLoader)
            } else {
                installedWith("en.fine", fineSource, fineLoader)
            }
        }

        val harness = CompatibilityHarness(
            dirs = ZoukanDirs(temp),
            storeService = storeService,
            installer = installer,
            loader = loader,
            options = CompatibilityHarness.Options(packages = listOf("en.blocking", "en.fine")),
            stepTimeout = 1.seconds,
        )
        val (_, rows) = harness.run()

        rows.map { it.pkg } shouldBe listOf("en.blocking", "en.fine")
        rows[0].error.lowercase() shouldContain "timed out"
        rows[1].error shouldBe "popular: empty"
    }

    private var loads = 0
    private val blockingLoader = mockk<ExtensionClassLoader>(relaxed = true)
    private val fineLoader = mockk<ExtensionClassLoader>(relaxed = true)

    /** Runs one extension: the blocking one (stuck in the latch) or the fine one, with a short step timeout. */
    private fun runOnly(pkg: String) {
        val store = mockk<ExtensionStore>()
        every { store.name } returns "Test store"
        every { store.signingKey } returns "0123456789abcdef0123456789abcdef"
        val storeService = mockk<ExtensionStoreService>()
        coEvery { storeService.fetch(any()) } returns Result.success(store)
        coEvery { storeService.getExtensions(store) } returns Result.success(listOf(available(pkg, store)))
        val installer = mockk<DesktopExtensionInstaller>()
        coEvery { installer.install(any(), any()) } returns mockk<DesktopExtensionInstaller.Installed>(relaxed = true) {
            every { method } returns DesktopExtensionInstaller.Method.JAR
        }
        val blockingSource = source().also {
            coEvery { it.getPopularManga(1) } coAnswers {
                latch.await()
                error("unreachable")
            }
        }
        val fineSource = source().also { coEvery { it.getPopularManga(1) } returns MangasPage(emptyList(), false) }
        val loader = mockk<DesktopExtensionLoader>()
        every { loader.load(any(), any()) } answers {
            if (pkg == "en.blocking") {
                installedWith(pkg, blockingSource, blockingLoader)
            } else {
                installedWith(pkg, fineSource, fineLoader)
            }
        }
        CompatibilityHarness(
            dirs = ZoukanDirs(temp),
            storeService = storeService,
            installer = installer,
            loader = loader,
            options = CompatibilityHarness.Options(packages = listOf(pkg)),
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
