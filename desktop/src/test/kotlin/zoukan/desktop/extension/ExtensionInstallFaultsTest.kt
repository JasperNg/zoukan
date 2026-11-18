package zoukan.desktop.extension

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.runBlocking
import mihon.domain.extension.model.ExtensionStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import zoukan.desktop.e2e.FixtureFailure
import zoukan.desktop.e2e.FixtureServer
import zoukan.desktop.e2e.FixtureStore
import zoukan.desktop.testing.FileSystemSandbox
import zoukan.desktop.testing.fakes.FileFaults
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.ExtensionException
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Installing an extension from a store (DesktopExtensionInstaller, with a real JAR-signed jar from
 * the fixture store) under injected faults: the jar's download failing or arriving damaged, and an
 * extensions folder that cannot be written. For each: the install fails with an error the caller
 * reports (an [ExtensionException], which DesktopExtensionManager returns as a load error; another
 * exception reaches the Extensions screen's error toast), the installer works again once the fault
 * is healed, and the installed copy of the extension keeps its bytes, with no temporary file left.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtensionInstallFaultsTest {
    @TempDir
    lateinit var temp: File

    // Built once: making the signed jars takes a few seconds
    private val server = FixtureServer()
    private lateinit var store: FixtureStore

    @BeforeAll
    fun buildStore() {
        store = FixtureStore(server)
    }

    @AfterAll
    fun closeStore() {
        store.close()
        server.close()
    }

    @AfterEach
    fun heal() {
        server.clearFailures()
        // The folder is the class's (the store is built once): each test starts with no extension
        FileSystemSandbox.deleteTree(extensionsDir)
    }

    private val extensionsDir get() = File(temp, "extensions")
    private val packageDir get() = File(extensionsDir, FixtureStore.TRUSTED_PKG)

    /** Sends the store's https addresses to the fixture server, as the store is served over https. */
    private val client by lazy {
        val fixture = server.baseUrl.toHttpUrl()
        // A short read timeout, so a stalled download fails within the test (the app's client waits 30 s)
        OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).addInterceptor { chain ->
            val url = chain.request().url
            if (url.isHttps && url.host == STORE_HOST) {
                chain.proceed(chain.request().newBuilder().url(url.newBuilder().scheme("http").host(fixture.host).port(fixture.port).build()).build())
            } else {
                chain.proceed(chain.request())
            }
        }.build()
    }

    private fun installer() = DesktopExtensionInstaller(extensionsDir) { client }

    private val extension by lazy {
        Extension.Available(
            name = FixtureStore.TRUSTED_NAME,
            pkgName = FixtureStore.TRUSTED_PKG,
            versionName = "1.4.1",
            versionCode = 1,
            libVersion = 1.4,
            lang = "all",
            isNsfw = false,
            signatureHash = store.storeKey.fingerprint,
            storeName = "Fixture store",
            sources = emptyList(),
            apkUrl = "https://$STORE_HOST/store/${FixtureStore.TRUSTED_PKG}.apk",
            iconUrl = "",
            store = ExtensionStore(
                indexUrl = "https://$STORE_HOST/store/index.min.json",
                name = "Fixture store",
                badgeLabel = "",
                signingKey = store.storeKey.fingerprint,
                contact = ExtensionStore.Contact(website = "https://$STORE_HOST", discord = null),
                isLegacy = false,
                extensionListUrl = null,
            ),
            jarUrl = "https://$STORE_HOST${store.jarPath(store.trusted)}",
        )
    }

    /** Every file under the extensions folder with its bytes, to compare before and after. */
    private fun files(): Map<String, List<Byte>> =
        extensionsDir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(extensionsDir).invariantSeparatorsPath to it.readBytes().toList() }

    /** Installs the extension once, healthy; returns what is on disk. */
    private fun installed(): Map<String, List<Byte>> {
        val done = runBlocking { installer().install(extension) }
        installer().dropPrevious(done)
        return files().also { it.keys shouldBe setOf("${FixtureStore.TRUSTED_PKG}/1.jar") }
    }

    /** After the fault is healed, a reinstall of the same version works and leaves the same files. */
    private fun reinstallWorks(before: Map<String, List<Byte>>) {
        server.clearFailures()
        val done = runBlocking { installer().install(extension) }
        done.files.jar.isFile shouldBe true
        installer().dropPrevious(done)
        files() shouldBe before
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("downloadFaults")
    fun `a jar download that fails or arrives damaged is refused with an error and the installed copy is kept`(name: String, failure: FixtureFailure) {
        val before = installed()
        server.fail(store.jarPath(store.trusted), failure)

        // Any exception reaches the user: DesktopExtensionManager returns an ExtensionException as a
        // load error, and both callers (the Extensions screen, the missing-extensions install) show
        // any other as an error toast. A damaged jar is an ExtensionException (the test below).
        val error = shouldThrow<Exception> { runBlocking { installer().install(extension) } }

        error.message.shouldNotBeBlank()
        // The installed copy keeps its bytes; no temporary download or kept-aside file is left
        files() shouldBe before
        reinstallWorks(before)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("damagedJars")
    fun `a damaged jar is refused as an extension error`(name: String, failure: FixtureFailure) {
        val before = installed()
        server.fail(store.jarPath(store.trusted), failure)

        // DesktopExtensionManager returns an ExtensionException as a load error (loadErrorsFlow)
        shouldThrow<ExtensionException> { runBlocking { installer().install(extension) } }

        // Never installed, nor left on disk under any name; the installed copy keeps its bytes
        files() shouldBe before
    }

    @Test
    fun `a file where the extensions folder should be fails the install with an extension error and is left alone`() {
        val blocker = FileFaults.fileInTheWay(extensionsDir)

        // An ExtensionException, which DesktopExtensionManager returns as a load error
        val error = shouldThrow<ExtensionException> { runBlocking { installer().install(extension) } }

        error.stage shouldBe ExtensionException.Stage.DOWNLOAD
        error.message.shouldNotBeBlank()
        blocker.readText() shouldBe "A file where a folder is needed"
        blocker.delete() shouldBe true
        reinstallWorks(installed())
    }

    @Test
    fun `a package folder that cannot be written fails a reinstall with an error and keeps the installed copy`() {
        val before = installed()
        val readOnly = FileFaults.readOnly(packageDir)
        assumeTrue(readOnly != null, "This OS or user can write in a read-only folder")

        readOnly!!.use {
            shouldThrow<Exception> { runBlocking { installer().install(extension) } }.message.shouldNotBeBlank()
        }

        files() shouldBe before
        reinstallWorks(before)
    }

    companion object {
        const val STORE_HOST = "store.fixture.test"

        @JvmStatic
        fun downloadFaults() = listOf(
            arrayOf("HTTP 500", FixtureFailure.Status(500)),
            arrayOf("connection cut mid-body", FixtureFailure.Truncate(keepBytes = 1000)),
            arrayOf("connection closed without an answer", FixtureFailure.Drop),
            arrayOf("corrupt body", FixtureFailure.Corrupt(fromByte = 200)),
            arrayOf("an HTML page with status 200", FixtureFailure.Answer(200, "text/html", "<html>Not found</html>".toByteArray())),
            arrayOf("a stall mid-body (the read timeout)", FixtureFailure.Stall(afterBytes = 1000)),
        )

        @JvmStatic
        fun damagedJars() = downloadFaults().filter { it[0] == "corrupt body" || it[0] == "an HTML page with status 200" }
    }
}
