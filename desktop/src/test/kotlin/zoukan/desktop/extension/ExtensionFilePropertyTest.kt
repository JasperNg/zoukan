package zoukan.desktop.extension

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.property.checkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.domain.extension.model.ExtensionStore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.e2e.FixtureServer
import zoukan.desktop.e2e.FixtureStore
import zoukan.desktop.property.Counterexamples
import zoukan.desktop.property.Mutations
import zoukan.desktop.property.PropertyRuns
import zoukan.desktop.property.arbFrom
import zoukan.desktop.testing.FileSystemSandbox
import zoukan.extension.ApkConverter
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.ExtensionException
import zoukan.extension.ExtensionManifest
import zoukan.extension.ExtensionSignatures
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * An extension file is untrusted until its signature is checked, so every step that reads one
 * (the JAR and APK signature checks, the manifest readers, the APK conversion, and the installer
 * that runs them) must, for any bytes, either succeed with one signer or fail with an
 * [ExtensionException], which the extension manager reports as an extension error. Any other
 * exception would reach the user as an unexplained failure, or be taken for a bug in the app.
 *
 * The inputs are the fixture store's signed jar and an APK signed here with the same key, broken in
 * [Mutations]' ways (cut off, chunks deleted, repeated or swapped, bytes flipped, odd text put in).
 * A file that fails is never installed: the package folder holds nothing afterwards.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtensionFilePropertyTest {
    @TempDir
    lateinit var temp: File

    private val server = FixtureServer()
    private lateinit var store: FixtureStore
    private lateinit var signedJar: ByteArray
    private lateinit var signedApk: ByteArray

    @BeforeAll
    fun buildFixtures() {
        store = FixtureStore(server)
        val request = Request.Builder().url(server.baseUrl + store.jarPath(store.trusted)).build()
        signedJar = OkHttpClient().newCall(request).execute().use { it.body.bytes() }
        signedApk = FixtureApk.signed(store.storeKey, FixtureStore.TRUSTED_PKG)
    }

    @AfterAll
    fun close() {
        store.close()
        server.close()
    }

    /** What the steps did with the inputs: anything but success or an [ExtensionException] is a finding. */
    private class Outcomes {
        val found = Counterexamples<String>()
        var succeeded = 0
        var refused = 0

        inline fun run(step: String, input: ByteArray, block: () -> Unit) {
            try {
                block()
                succeeded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExtensionException) {
                refused++
            } catch (e: Throwable) {
                found.add("$step: ${e::class.qualifiedName}", input.size.toString(), e.toString().take(300))
            }
        }

        fun check() {
            println("$succeeded succeeded, $refused refused as extension errors")
            check(found.kinds().isEmpty()) {
                "Not an ExtensionException (seed ${PropertyRuns.seed}; input = its size in bytes):\n" + found.report()
            }
        }
    }

    @Test
    fun `a damaged signed jar verifies with its signer or is refused as an extension error`() = runBlocking<Unit> {
        val outcomes = Outcomes()
        val file = File(temp, "candidate.jar")
        checkAll(PropertyRuns.config(ITERATIONS), arbFrom { Mutations.input(it, listOf(signedJar), Mutations.Format.BINARY) }) { input ->
            file.writeBytes(input)
            outcomes.run("verifyJar", input) {
                val verified = ExtensionSignatures.verifyJar(file)
                // One signer. Not always the store's key: damage to the certificate's own fields (its
                // name, dates) leaves the signature valid, as its key is unchanged, but changes the
                // certificate's fingerprint, so the extension is untrusted when it loads
                check(verified.signers.size == 1) { "Verified with signers ${verified.signers}" }
                ExtensionManifest.fromContents(verified.contents, file.name)
            }
            outcomes.run("fromJar", input) { ExtensionManifest.fromJar(file) }
        }
        outcomes.check()
    }

    @Test
    fun `a damaged signed APK verifies with its signer or is refused as an extension error`() = runBlocking<Unit> {
        val outcomes = Outcomes()
        val jar = File(temp, "converted.jar")
        checkAll(PropertyRuns.config(ITERATIONS), arbFrom { Mutations.input(it, listOf(signedApk), Mutations.Format.BINARY) }) { input ->
            outcomes.run("verifyApk", input) {
                // As for a jar, a damaged certificate field can leave a valid signature by another fingerprint
                val signers = ExtensionSignatures.verifyApk(input)
                check(signers.size == 1) { "Verified with signers $signers" }
            }
            // The readers after the check, each given the damaged bytes as they are
            outcomes.run("fromApk", input) { ExtensionManifest.fromApk(input) }
            outcomes.run("manifestXmlOfApk", input) { ExtensionManifest.manifestXmlOfApk(input) }
            jar.delete()
            outcomes.run("convert", input) {
                try {
                    ApkConverter.convert(input, jar)
                } catch (e: ExtensionException) {
                    check(!jar.exists()) { "A failed conversion left ${jar.name}" }
                    throw e
                }
            }
        }
        outcomes.check()
    }

    @Test
    fun `an APK with a broken length anywhere is refused as an extension error`() {
        // Every 4 bytes of the APK in turn set to a negative or huge number, as a crafted length
        // field would be: in the v1 signature's files apksig then throws an IllegalArgumentException
        // ("Negative length") of its own
        val outcomes = Outcomes()
        for (offset in 0..signedApk.size - 4) {
            for (top in listOf(0xff, 0x80)) {
                val input = signedApk.copyOf()
                for (i in 0 until 4) input[offset + i] = (if (i == 3) top else 0xff).toByte()
                outcomes.run("verifyApk", input) { ExtensionSignatures.verifyApk(input) }
            }
        }
        outcomes.check()
    }

    @Test
    fun `the installer refuses a damaged jar or APK as an extension error and leaves no file`() = runBlocking<Unit> {
        val outcomes = Outcomes()
        val extensionsDir = File(temp, "extensions")
        val installer = DesktopExtensionInstaller(extensionsDir) { client }
        checkAll(
            PropertyRuns.config(ITERATIONS / 5),
            arbFrom { r -> r.nextBoolean().let { apk -> apk to Mutations.input(r, listOf(if (apk) signedApk else signedJar), Mutations.Format.BINARY) } },
        ) { (apk, input) ->
            FileSystemSandbox.deleteTree(extensionsDir)
            body = input
            outcomes.run(if (apk) "install APK" else "install jar", input) {
                try {
                    val installed = installer.install(extension(), forceApk = apk)
                    installer.dropPrevious(installed)
                } catch (e: ExtensionException) {
                    val left = extensionsDir.walkTopDown().filter { it.isFile }.toList()
                    check(left.isEmpty()) { "A refused file left $left" }
                    throw e
                }
            }
        }
        outcomes.check()
    }

    /** What [client] answers every request with. */
    @Volatile
    private var body = ByteArray(0)

    /** One client for every try: each client has its own threads. */
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody()).build()
    }.build()

    private fun extension() = Extension.Available(
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
        jarUrl = "https://$STORE_HOST/store/${FixtureStore.TRUSTED_PKG}.jar",
    )

    private companion object {
        const val ITERATIONS = 400
        const val STORE_HOST = "store.fixture.test"
    }
}
