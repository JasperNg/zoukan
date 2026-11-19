package zoukan.desktop.extension

import com.android.apksig.ApkSigner
import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.property.checkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.domain.extension.model.ExtensionStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import zoukan.desktop.e2e.FixtureFailure
import zoukan.desktop.e2e.FixtureServer
import zoukan.desktop.e2e.FixtureStore
import zoukan.desktop.property.Counterexamples
import zoukan.desktop.property.Mutations
import zoukan.desktop.property.PropertyRuns
import zoukan.desktop.property.arbFrom
import zoukan.desktop.testing.FileSystemSandbox
import zoukan.desktop.testing.fakes.FileFaults
import zoukan.extension.ApkConverter
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.ExtensionException
import zoukan.extension.ExtensionManifest
import zoukan.extension.ExtensionSignatures
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val FIXTURE_STORE_HOST = "store.fixture.test"

private fun fixtureAvailableExtension(store: FixtureStore, jarUrl: String) = Extension.Available(
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
    apkUrl = "https://$FIXTURE_STORE_HOST/store/${FixtureStore.TRUSTED_PKG}.apk",
    iconUrl = "",
    store = ExtensionStore(
        indexUrl = "https://$FIXTURE_STORE_HOST/store/index.min.json",
        name = "Fixture store",
        badgeLabel = "",
        signingKey = store.storeKey.fingerprint,
        contact = ExtensionStore.Contact(website = "https://$FIXTURE_STORE_HOST", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    ),
    jarUrl = jarUrl,
)

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
        fixtureAvailableExtension(store, "https://$STORE_HOST${store.jarPath(store.trusted)}")
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
            // An ExtensionException, which DesktopExtensionManager returns as a load error
            val error = shouldThrow<ExtensionException> { runBlocking { installer().install(extension) } }
            error.stage shouldBe ExtensionException.Stage.DOWNLOAD
            error.message.shouldNotBeBlank()
        }

        files() shouldBe before
        reinstallWorks(before)
    }

    @Test
    fun `a package folder that cannot be written fails an APK reinstall with an extension error and keeps the installed copy`() {
        val before = installed()
        server.fail("/store/${FixtureStore.TRUSTED_PKG}.apk", FixtureFailure.Answer(200, "application/vnd.android.package-archive", FixtureApk.signed(store.storeKey, FixtureStore.TRUSTED_PKG)))
        val readOnly = FileFaults.readOnly(packageDir)
        assumeTrue(readOnly != null, "This OS or user can write in a read-only folder")

        readOnly!!.use {
            // The conversion's temporary files go in the package folder, which cannot be written
            val error = shouldThrow<ExtensionException> { runBlocking { installer().install(extension, forceApk = true) } }
            error.stage shouldBe ExtensionException.Stage.DOWNLOAD
            error.message.shouldNotBeBlank()
        }

        files() shouldBe before
    }

    @Test
    fun `a file where the package folder should be fails the install with an extension error and is left alone`() {
        val blocker = FileFaults.fileInTheWay(packageDir)

        for (apk in listOf(false, true)) {
            if (apk) server.fail("/store/${FixtureStore.TRUSTED_PKG}.apk", FixtureFailure.Answer(200, "application/vnd.android.package-archive", FixtureApk.signed(store.storeKey, FixtureStore.TRUSTED_PKG)))
            val error = shouldThrow<ExtensionException> { runBlocking { installer().install(extension, forceApk = apk) } }
            error.stage shouldBe ExtensionException.Stage.DOWNLOAD
            error.message.shouldNotBeBlank()
            blocker.readText() shouldBe "A file where a folder is needed"
        }
        // Nothing else was written: no temporary download is left next to it
        files().keys shouldBe setOf(FixtureStore.TRUSTED_PKG)
        blocker.delete() shouldBe true
        reinstallWorks(installed())
    }

    companion object {
        const val STORE_HOST = FIXTURE_STORE_HOST

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

    private fun extension() = fixtureAvailableExtension(
        store,
        "https://$FIXTURE_STORE_HOST/store/${FixtureStore.TRUSTED_PKG}.jar",
    )

    private companion object {
        const val ITERATIONS = 400
    }
}

/** An extension APK as a store that publishes only APKs serves it, signed with a fixture store's key. */
object FixtureApk {
    /**
     * A small APK (a binary manifest an extension would have, an asset, no code), signed with the
     * v1 and v2 schemes by the store's key: its signature verifies and its manifest reads.
     */
    fun signed(key: FixtureStore.Key, pkg: String): ByteArray {
        val dir = Files.createTempDirectory("zoukan-fixture-apk").toFile()
        val unsigned = File(dir, "unsigned.apk")
        ZipOutputStream(unsigned.outputStream()).use { out ->
            mapOf(
                "AndroidManifest.xml" to BinaryManifest.extension(pkg),
                "assets/readme.txt" to "fixture".toByteArray(),
            ).forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        val ks = KeyStore.getInstance("PKCS12").apply { key.keystore.inputStream().use { load(it, FixtureStore.PASSWORD.toCharArray()) } }
        val signer = ApkSigner.SignerConfig.Builder(
            "fixture",
            ks.getKey(key.alias, FixtureStore.PASSWORD.toCharArray()) as PrivateKey,
            listOf(ks.getCertificate(key.alias) as X509Certificate),
        ).build()
        val signed = File(dir, "signed.apk")
        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setMinSdkVersion(24)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .build()
            .sign()
        // The fixture itself verifies and reads, as a store's APK does
        val bytes = signed.readBytes()
        FileSystemSandbox.deleteTree(dir)
        check(ExtensionSignatures.verifyApk(bytes) == listOf(key.fingerprint))
        check(ExtensionManifest.fromApk(bytes).packageName == pkg)
        return bytes
    }
}

/**
 * Android's binary XML (AXML) for a small extension manifest: a string pool, the attributes'
 * resource ids, and the elements, as aapt writes them, so apksig and the manifest reader can read it.
 */
private object BinaryManifest {
    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    private class Attr(val name: String, val resId: Int, val value: Any, val android: Boolean = true)
    private class El(val name: String, val attrs: List<Attr>, val children: List<El> = emptyList())

    fun extension(pkg: String): ByteArray = encode(
        El(
            "manifest",
            listOf(
                Attr("versionCode", 0x0101021b, 1),
                Attr("versionName", 0x0101021c, "1.4.1"),
                Attr("package", 0, pkg, android = false),
            ),
            listOf(
                El("uses-sdk", listOf(Attr("minSdkVersion", 0x0101020c, 24))),
                El("uses-feature", listOf(Attr("name", 0x01010003, ExtensionManifest.EXTENSION_FEATURE))),
                El(
                    "application",
                    listOf(Attr("label", 0x01010001, "Tachiyomi: Fixture")),
                    listOf(El("meta-data", listOf(Attr("name", 0x01010003, ExtensionManifest.METADATA_SOURCE_CLASS), Attr("value", 0x01010024, ".Fixture")))),
                ),
            ),
        ),
    )

    private fun encode(root: El): ByteArray {
        fun all(e: El): List<El> = listOf(e) + e.children.flatMap(::all)
        val elements = all(root)
        // Attribute names with a resource id come first, in the order of the resource map
        val withIds = elements.flatMap { it.attrs }.filter { it.resId != 0 }.map { it.name to it.resId }.distinct()
        val strings = LinkedHashSet<String>()
        withIds.forEach { strings += it.first }
        strings += listOf("android", ANDROID)
        elements.forEach { e ->
            strings += e.name
            e.attrs.forEach { a ->
                strings += a.name
                (a.value as? String)?.let { strings += it }
            }
        }
        val index = strings.withIndex().associate { it.value to it.index }
        val body = ByteArrayOutputStream()
        body.write(stringPool(strings.toList()))
        body.write(chunk(0x0180, 8) { withIds.forEach { (_, id) -> putInt(id) } })
        body.write(
            chunk(0x0100, 16, 24) {
                line()
                putInt(index.getValue("android"))
                putInt(index.getValue(ANDROID))
            },
        )
        fun write(e: El) {
            body.write(
                chunk(0x0102, 16, 36 + 20 * e.attrs.size) {
                    line()
                    putInt(-1)
                    putInt(index.getValue(e.name))
                    putShort(20)
                    putShort(20)
                    putShort(e.attrs.size.toShort())
                    putShort(0)
                    putShort(0)
                    putShort(0)
                    e.attrs.forEach { a ->
                        putInt(if (a.android) index.getValue(ANDROID) else -1)
                        putInt(index.getValue(a.name))
                        val text = a.value as? String
                        putInt(text?.let(index::getValue) ?: -1)
                        putShort(8)
                        put(0)
                        // TYPE_STRING or TYPE_INT_DEC
                        put(if (text != null) 0x03 else 0x10)
                        putInt(text?.let(index::getValue) ?: a.value as Int)
                    }
                },
            )
            e.children.forEach(::write)
            body.write(
                chunk(0x0103, 16, 24) {
                    line()
                    putInt(-1)
                    putInt(index.getValue(e.name))
                },
            )
        }
        write(root)
        body.write(
            chunk(0x0101, 16, 24) {
                line()
                putInt(index.getValue("android"))
                putInt(index.getValue(ANDROID))
            },
        )
        val content = body.toByteArray()
        // The file's own chunk header holds every other chunk
        return chunk(0x0003, 8) { put(content) }
    }

    private fun ByteBuffer.line() {
        putInt(1)
        putInt(-1)
    }

    /** A UTF-16 string pool. */
    private fun stringPool(strings: List<String>): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = strings.map { s ->
            val offset = data.size()
            val b = ByteBuffer.allocate(4 + 2 * s.length).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(s.length.toShort())
            s.forEach { b.putChar(it) }
            b.putShort(0)
            data.write(b.array())
            offset
        }
        while (data.size() % 4 != 0) data.write(0)
        val headerSize = 28
        val stringsStart = headerSize + 4 * strings.size
        return chunk(0x0001, headerSize, stringsStart + data.size()) {
            putInt(strings.size)
            putInt(0)
            putInt(0)
            putInt(stringsStart)
            putInt(0)
            offsets.forEach { putInt(it) }
            put(data.toByteArray())
        }
    }

    /** A chunk: its type, header size and total size, then what [fill] writes. */
    private fun chunk(type: Int, headerSize: Int, size: Int? = null, fill: ByteBuffer.() -> Unit): ByteArray {
        val b = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(type.toShort())
        b.putShort(headerSize.toShort())
        b.putInt(0)
        b.fill()
        val total = size ?: b.position()
        check(total == b.position()) { "Chunk 0x${type.toString(16)} is ${b.position()} bytes, not $total" }
        b.putInt(4, total)
        return b.array().copyOf(total)
    }
}
