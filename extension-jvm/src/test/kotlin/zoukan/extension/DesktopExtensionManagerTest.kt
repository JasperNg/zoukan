package zoukan.extension

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Updates are pinned to the installed signer, as on Android: a build signed with another key is
 * refused, and stays refused however often it is tried, because the pin comes from what is
 * installed on disk rather than from what happened to load.
 */
class DesktopExtensionManagerTest {
    private val pkgName = "zoukan.extension.test.sample"
    private val sourceClass = "zoukan.extension.TestExtensionFactory"

    @TempDir
    lateinit var extensionsDir: File

    /** Where the fake store's files are served from; outside [extensionsDir]. */
    @TempDir
    lateinit var storeDir: File

    private fun manifest(versionCode: Long, sourceClass: String = this.sourceClass) = """
        <manifest package="$pkgName" android:versionCode="$versionCode" android:versionName="1.4.$versionCode"
            xmlns:android="http://schemas.android.com/apk/res/android">
          <uses-feature android:name="tachiyomi.extension" />
          <application android:label="Sample">
            <meta-data android:name="tachiyomi.extension.class" android:value="$sourceClass" />
            <meta-data android:name="tachiyomix.extensionLib" android:value="1.4" />
          </application>
        </manifest>
    """.trimIndent()

    /**
     * A test extension jar. Its "signature" is a [SIGNER_ENTRY] naming the key, which the stubbed
     * [ExtensionSignatures.verifyJar] reports as the signing certificate.
     */
    private fun writeJar(jar: File, versionCode: Long, signer: String, sourceClass: String = this.sourceClass) {
        jar.parentFile.mkdirs()
        ZipOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("AndroidManifest.xml"))
            out.write(manifest(versionCode, sourceClass).toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry(SIGNER_ENTRY))
            out.write(signer.toByteArray())
            out.closeEntry()
        }
    }

    private fun signerOf(jar: File): String = ZipFile(jar).use { zip ->
        zip.getInputStream(zip.getEntry(SIGNER_ENTRY)).use { it.readAllBytes().decodeToString() }
    }

    /** While set, store downloads wait for it (to hold an install half way). */
    @Volatile
    private var gate: CountDownLatch? = null

    /** Serves `<storeDir>/<file name>` for any URL ending in that name. */
    private val storeClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            gate?.await()
            val file = File(storeDir, chain.request().url.pathSegments.last())
            if (!file.isFile) throw IOException("404 ${file.name}")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(file.readBytes().toResponseBody())
                .build()
        }
        .build()

    private fun store(name: String, key: String) = ExtensionStore(
        indexUrl = "https://$name.example/index.pb",
        name = name,
        badgeLabel = name,
        signingKey = key,
        contact = ExtensionStore.Contact("https://$name.example", null),
        isLegacy = false,
        extensionListUrl = null,
    )

    /** An offer of [versionCode] from the store of [signer], whose jar is signed by that key. */
    private fun offer(
        versionCode: Long,
        signer: String,
        sourceClass: String = this.sourceClass,
        listedVersionCode: Long = versionCode,
    ): Extension.Available {
        val fileName = "$signer-$versionCode-${sourceClass.hashCode()}.jar"
        writeJar(File(storeDir, fileName), versionCode, signer, sourceClass)
        val store = store("store-$signer", signer)
        return Extension.Available(
            name = "Sample",
            pkgName = pkgName,
            versionName = "1.4.$versionCode",
            versionCode = listedVersionCode,
            libVersion = 1.4,
            lang = "all",
            isNsfw = false,
            signatureHash = signer,
            storeName = store.name,
            sources = emptyList(),
            apkUrl = "https://${store.name}.example/$signer-$versionCode.apk",
            iconUrl = "",
            store = store,
            jarUrl = "https://${store.name}.example/$fileName",
        )
    }

    private fun manager(): DesktopExtensionManager {
        val trust = mockk<ExtensionTrust> { every { isTrusted(any(), any(), any(), any()) } returns true }
        return DesktopExtensionManager(
            loader = DesktopExtensionLoader(extensionsDir, trust, FakeConvertedJarHashes()),
            installer = DesktopExtensionInstaller(extensionsDir) { storeClient },
            trust = trust,
            storeService = mockk<ExtensionStoreService>(),
            getStores = { emptyList() },
        )
    }

    private fun withStubbedSignatures(block: suspend () -> Unit) {
        mockkObject(ExtensionSignatures) {
            every { ExtensionSignatures.verifyJar(any()) } answers {
                val jar = firstArg<File>()
                ExtensionSignatures.VerifiedJar(listOf(signerOf(jar)), JarContents.fromBytes(jar.readBytes()))
            }
            runBlocking { block() }
        }
    }

    private fun installedVersions(): List<String> =
        File(extensionsDir, pkgName).listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun `an update signed by another key stays refused after a failed attempt`() = withStubbedSignatures {
        val manager = manager()
        manager.installExtension(offer(3, "key-a")).shouldBeInstanceOf<LoadResult.Success>()

        // The first attempt fails the signer check; the second must not find the pin gone
        repeat(2) {
            val result = manager.installExtension(offer(4, "key-b"))

            result.shouldBeInstanceOf<LoadResult.Error>().error.stage shouldBe ExtensionException.Stage.SIGNATURE
            // The installed extension is still listed, and still the old version
            val installed = manager.installedExtensionsFlow.value[pkgName].shouldBeInstanceOf<Extension.Installed>()
            installed.versionCode shouldBe 3
            installed.signatureHash shouldBe "key-a"
            manager.loadErrorsFlow.value[pkgName] shouldBe null
        }
        installedVersions() shouldBe listOf("3.jar")

        // The same key's update still installs
        manager.installExtension(offer(4, "key-a")).shouldBeInstanceOf<LoadResult.Success>()
            .extension.versionCode shouldBe 4
    }

    @Test
    fun `the pin comes from the installed files, also when nothing is loaded`() = withStubbedSignatures {
        // Installed by key A, but not loaded: it failed to load, or the app has not loaded it yet
        writeJar(File(extensionsDir, "$pkgName/3.jar"), 3, "key-a")
        val manager = manager()
        manager.installedExtensionsFlow.value[pkgName] shouldBe null

        repeat(2) {
            manager.installExtension(offer(4, "key-b"))
                .shouldBeInstanceOf<LoadResult.Error>().error.stage shouldBe ExtensionException.Stage.SIGNATURE
        }
        installedVersions() shouldBe listOf("3.jar")
        signerOf(File(extensionsDir, "$pkgName/3.jar")) shouldBe "key-a"
    }

    @Test
    fun `a cancelled download is cancelled, not reported as a download error`() {
        val release = CountDownLatch(1)
        val hangingClient = OkHttpClient.Builder()
            .addInterceptor {
                release.await()
                throw IOException("released")
            }
            .build()
        val installer = DesktopExtensionInstaller(extensionsDir) { hangingClient }
        try {
            // withTimeoutOrNull only returns null when its cancellation came back out of the install;
            // an ExtensionException in its place would be thrown from here
            runBlocking { withTimeoutOrNull(300) { installer.install(offer(4, "key-a")) } } shouldBe null
            // The partial download was cleaned up
            extensionsDir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(pkgName) }.shouldBeEmpty()
        } finally {
            release.countDown()
            hangingClient.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `an update that fails to load keeps the working version`() = withStubbedSignatures {
        val manager = manager()
        manager.installExtension(offer(3, "key-a")).shouldBeInstanceOf<LoadResult.Success>()

        // Same signer, newer version, but its source class does not exist
        val result = manager.installExtension(offer(4, "key-a", sourceClass = "zoukan.extension.Missing"))

        result.shouldBeInstanceOf<LoadResult.Error>().error.stage shouldBe ExtensionException.Stage.LOAD
        manager.installedExtensionsFlow.value[pkgName].shouldBeInstanceOf<Extension.Installed>().versionCode shouldBe 3
        manager.loadErrorsFlow.value[pkgName] shouldBe null
        // The broken files are gone, so the next start loads version 3 again
        installedVersions() shouldBe listOf("3.jar")
    }

    @Test
    fun `an older version is refused`() = withStubbedSignatures {
        val manager = manager()
        manager.installExtension(offer(4, "key-a")).shouldBeInstanceOf<LoadResult.Success>()

        manager.installExtension(offer(3, "key-a"))
            .shouldBeInstanceOf<LoadResult.Error>().error.stage shouldBe ExtensionException.Stage.MANIFEST

        manager.installedExtensionsFlow.value[pkgName]?.versionCode shouldBe 4
        installedVersions() shouldBe listOf("4.jar")
    }

    @Test
    fun `a file whose version is not the one the store lists is refused`() = withStubbedSignatures {
        manager().installExtension(offer(4, "key-a", listedVersionCode = 5))
            .shouldBeInstanceOf<LoadResult.Error>().error.stage shouldBe ExtensionException.Stage.MANIFEST
        installedVersions() shouldBe emptyList()
    }

    @Test
    fun `an uninstall during an install waits for it, and the extension stays uninstalled`() = withStubbedSignatures {
        val manager = manager()
        val release = CountDownLatch(1)
        gate = release
        coroutineScope {
            val install = async(Dispatchers.IO) { manager.installExtension(offer(3, "key-a")) }
            // Let the install take the package's lock and reach its download
            delay(300)
            val uninstall = launch(Dispatchers.IO) { manager.uninstall(pkgName) }
            delay(200)
            uninstall.isCompleted shouldBe false
            release.countDown()
            install.await().shouldBeInstanceOf<LoadResult.Success>()
            uninstall.join()
        }
        gate = null
        manager.installedExtensionsFlow.value[pkgName] shouldBe null
        File(extensionsDir, pkgName).exists() shouldBe false
    }

    @Test
    fun `an uninstall closes the class loader`() = withStubbedSignatures {
        val manager = manager()
        val loader = manager.installExtension(offer(3, "key-a"))
            .shouldBeInstanceOf<LoadResult.Success>().classLoader!!
        loader.isClosed shouldBe false

        manager.uninstall(pkgName)

        loader.isClosed shouldBe true
    }

    @Test
    fun `an update closes the old class loader only once the new version is listed`() = withStubbedSignatures {
        val manager = manager()
        val old = manager.installExtension(offer(3, "key-a"))
            .shouldBeInstanceOf<LoadResult.Success>().classLoader!!
        var listedWhenClosed: Long? = null
        old.onClose = { listedWhenClosed = manager.installedExtensionsFlow.value[pkgName]?.versionCode }

        val new = manager.installExtension(offer(4, "key-a"))
            .shouldBeInstanceOf<LoadResult.Success>().classLoader!!

        old.isClosed shouldBe true
        listedWhenClosed shouldBe 4
        new.isClosed shouldBe false
    }

    @Test
    fun `an update that fails to load keeps the working class loader open`() = withStubbedSignatures {
        val manager = manager()
        val working = manager.installExtension(offer(3, "key-a"))
            .shouldBeInstanceOf<LoadResult.Success>().classLoader!!

        manager.installExtension(offer(4, "key-a", sourceClass = "zoukan.extension.Missing"))
            .shouldBeInstanceOf<LoadResult.Error>()

        working.isClosed shouldBe false
    }

    private companion object {
        const val SIGNER_ENTRY = "test-signer.txt"
    }
}
