package zoukan.extension

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.runBlocking
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * NSFW extensions stay unloaded while NSFW sources are hidden, as in ExtensionLoader, and a
 * removed store's extensions leave the available list.
 */
class ExtensionNsfwAndStoreTest {
    private val pkgName = "zoukan.extension.test.nsfw"
    private val signer = "key-a"
    private val sourceClass = "zoukan.extension.TestExtensionFactory"

    @TempDir
    lateinit var extensionsDir: File

    private fun installJar(versionCode: Long, nsfw: Boolean): DesktopExtensionLoader.InstalledFiles {
        val jar = File(extensionsDir, "$pkgName/$versionCode.jar").apply { parentFile.mkdirs() }
        val nsfwMeta = if (nsfw) """<meta-data android:name="tachiyomi.extension.nsfw" android:value="1" />""" else ""
        val manifest = """
            <manifest package="$pkgName" android:versionCode="$versionCode" android:versionName="1.4.$versionCode"
                xmlns:android="http://schemas.android.com/apk/res/android">
              <uses-feature android:name="tachiyomi.extension" />
              <application android:label="Sample">
                <meta-data android:name="tachiyomi.extension.class" android:value="$sourceClass" />
                <meta-data android:name="tachiyomix.extensionLib" android:value="1.4" />
                $nsfwMeta
              </application>
            </manifest>
        """.trimIndent()
        ZipOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("AndroidManifest.xml"))
            out.write(manifest.toByteArray())
            out.closeEntry()
        }
        return DesktopExtensionLoader.InstalledFiles(pkgName, versionCode, jar, null)
    }

    private val trust = mockk<ExtensionTrust> { every { isTrusted(any(), any(), any(), any()) } returns true }

    private fun loader(showNsfw: () -> Boolean) =
        DesktopExtensionLoader(extensionsDir, trust, FakeConvertedJarHashes(), showNsfw)

    private fun withStubbedSignatures(block: suspend () -> Unit) {
        mockkObject(ExtensionSignatures) {
            every { ExtensionSignatures.verifyJar(any()) } answers {
                val jar = firstArg<File>()
                ExtensionSignatures.VerifiedJar(listOf(signer), JarContents.fromBytes(jar.readBytes()))
            }
            runBlocking { block() }
        }
    }

    @Test
    fun `an NSFW extension is not loaded while NSFW sources are hidden`() = withStubbedSignatures {
        val files = installJar(3, nsfw = true)

        loader { false }.load(files, emptyList())
            .shouldBeInstanceOf<LoadResult.Error>().error.stage shouldBe ExtensionException.Stage.LOAD
        loader { true }.load(files, emptyList())
            .shouldBeInstanceOf<LoadResult.Success>().extension.isNsfw shouldBe true
        // The refused files stay installed, for when the setting is turned on
        files.jar.isFile shouldBe true
    }

    @Test
    fun `a safe extension loads while NSFW sources are hidden`() = withStubbedSignatures {
        loader { false }.load(installJar(3, nsfw = false), emptyList())
            .shouldBeInstanceOf<LoadResult.Success>()
    }

    @Test
    fun `the setting is read once, so a change takes effect after a restart`() = withStubbedSignatures {
        var show = false
        val loader = loader { show }
        val files = installJar(3, nsfw = true)
        loader.load(files, emptyList()).shouldBeInstanceOf<LoadResult.Error>()

        show = true
        loader.load(files, emptyList()).shouldBeInstanceOf<LoadResult.Error>()
        // A new loader stands for the next start
        loader { show }.load(files, emptyList()).shouldBeInstanceOf<LoadResult.Success>()
    }

    private fun store(name: String) = ExtensionStore(
        indexUrl = "https://$name.example/index.pb",
        name = name,
        badgeLabel = name,
        signingKey = signer,
        contact = ExtensionStore.Contact("https://$name.example", null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private fun offer(pkg: String, versionCode: Long, store: ExtensionStore) = Extension.Available(
        name = pkg,
        pkgName = pkg,
        versionName = "1.4.$versionCode",
        versionCode = versionCode,
        libVersion = 1.4,
        lang = "all",
        isNsfw = false,
        signatureHash = signer,
        storeName = store.name,
        sources = emptyList(),
        apkUrl = "https://${store.name}.example/$pkg.apk",
        iconUrl = "",
        store = store,
    )

    @Test
    fun `a removed store's offers leave the available list and stop being updates`() = withStubbedSignatures {
        installJar(3, nsfw = false)
        val storeA = store("a")
        val storeB = store("b")
        val storeService = mockk<ExtensionStoreService> {
            coEvery { getExtensions(storeA) } returns Result.success(
                listOf(offer(pkgName, 4, storeA), offer("only.in.a", 1, storeA)),
            )
            coEvery { getExtensions(storeB) } returns Result.success(listOf(offer("only.in.b", 1, storeB)))
        }
        val manager = DesktopExtensionManager(
            loader = loader { true },
            installer = DesktopExtensionInstaller(extensionsDir) { error("no downloads") },
            trust = trust,
            storeService = storeService,
            getStores = { listOf(storeA, storeB) },
        )
        manager.init()
        manager.findAvailableExtensions()
        manager.availableExtensionsFlow.value.keys shouldBe setOf(pkgName, "only.in.a", "only.in.b")
        manager.installedExtensionsFlow.value.getValue(pkgName).let {
            it.hasUpdate shouldBe true
            it.store shouldBe storeA
        }

        manager.forgetStore(storeA.indexUrl)

        manager.availableExtensionsFlow.value.keys shouldBe setOf("only.in.b")
        manager.installedExtensionsFlow.value.getValue(pkgName).let {
            it.hasUpdate shouldBe false
            it.store shouldBe null
        }
    }
}
