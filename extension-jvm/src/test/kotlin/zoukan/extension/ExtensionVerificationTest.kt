package zoukan.extension

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockkObject
import kotlinx.coroutines.runBlocking
import mihon.data.extension.service.InsecureExtensionStoreException
import mihon.data.extension.service.awaitSecureStoreResponse
import mihon.data.extension.service.requireSecureStoreUrl
import mihon.domain.extension.model.ExtensionStore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What is checked before extension code runs: the JAR signature covers everything a class loader
 * can act on, the class loader runs the bytes that were checked, a failed conversion keeps the
 * installed copy, and store files never come over plain http, also not after a redirect.
 */
class ExtensionVerificationTest {
    @TempDir
    lateinit var dir: File

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun jdkTool(name: String): String = File(System.getProperty("java.home"), "bin/$name").path

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "${command.first()}: $output" }
    }

    /** Signs [jar] with jarsigner, as a store's jars are signed. */
    private fun sign(jar: File) {
        val keystore = File(dir, "test.p12")
        if (!keystore.exists()) {
            run(
                jdkTool("keytool"), "-genkeypair", "-keystore", keystore.path, "-storetype", "PKCS12",
                "-storepass", "password", "-keypass", "password", "-alias", "store", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "365", "-dname", "CN=Test store",
            )
        }
        run(jdkTool("jarsigner"), "-keystore", keystore.path, "-storepass", "password", jar.path, "store")
    }

    private val extensionFiles = mapOf(
        "AndroidManifest.xml" to "<manifest/>".toByteArray(),
        "zoukan/extension/x/Source.class" to byteArrayOf(1, 2, 3),
        "META-INF/services/java.lang.Runnable" to "x.Y".toByteArray(),
        "assets/data.json" to "{}".toByteArray(),
    )

    @Test
    fun `a signed jar verifies, META-INF files included, and its files come back`() {
        val jar = File(dir, "signed.jar")
        writeZip(jar, extensionFiles)
        sign(jar)

        val verified = ExtensionSignatures.verifyJar(jar)

        verified.signers.size shouldBe 1
        verified.contents.entries.keys shouldBe extensionFiles.keys
        verified.contents["assets/data.json"]!!.decodeToString() shouldBe "{}"
    }

    @Test
    fun `an unsigned file added to META-INF after signing is refused`() {
        val signed = File(dir, "signed.jar")
        writeZip(signed, extensionFiles)
        sign(signed)
        // The signed jar's entries plus one more service file
        val tampered = File(dir, "tampered.jar")
        java.util.zip.ZipFile(signed).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }
                .associate { it.name to zip.getInputStream(it).readAllBytes() }
            writeZip(tampered, entries + ("META-INF/services/java.sql.Driver" to "evil.Driver".toByteArray()))
        }

        shouldThrow<ExtensionException> { ExtensionSignatures.verifyJar(tampered) }
            .message shouldContain "META-INF/services/java.sql.Driver is not signed"
    }

    @Test
    fun `only the manifest and the signature files are signing related`() {
        listOf(
            "META-INF/MANIFEST.MF",
            "META-INF/STORE.SF",
            "META-INF/store.rsa",
            "META-INF/A.DSA",
            "META-INF/A.EC",
            "META-INF/SIG-X.ABC",
        ).forEach { ExtensionSignatures.isSigningRelated(it) shouldBe true }
        listOf(
            "META-INF/services/x.Y",
            "META-INF/versions/11/a/B.class",
            "META-INF/main.kotlin_module",
            "META-INF/sub/A.SF",
            "a/MANIFEST.MF",
            "AndroidManifest.xml",
        ).forEach { ExtensionSignatures.isSigningRelated(it) shouldBe false }
    }

    /** A class not on the app's class path, so only the extension's class loader can define it. */
    private fun generatedClass(name: String, greeting: String): ByteArray {
        val internal = name.replace('.', '/')
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "greet", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(greeting)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `the class loader runs the bytes it was given and serves their resources`() {
        val name = "zoukan.extension.generated.Greeter"
        val jar = File(dir, "ext.jar")
        writeZip(
            jar,
            mapOf(
                name.replace('.', '/') + ".class" to generatedClass(name, "hello"),
                "assets/data.json" to "{\"a\":1}".toByteArray(),
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
            ),
        )
        val contents = JarContents.fromBytes(jar.readBytes())
        // The file is gone: whatever runs now comes from the bytes read before
        jar.delete() shouldBe true
        val loader = ExtensionClassLoader(contents, "zoukan.extension.generated")

        loader.loadClass(name).getMethod("greet").invoke(null) shouldBe "hello"
        loader.getResourceAsStream("assets/data.json")!!.readAllBytes().decodeToString() shouldBe "{\"a\":1}"
        loader.getResource("assets/data.json")!!.openStream().use { it.readAllBytes().decodeToString() } shouldBe
            "{\"a\":1}"
        // Host classes still come from the app
        loader.loadClass("kotlin.Unit") shouldBe Unit::class.java
        contents.entries.keys.contains("META-INF/MANIFEST.MF") shouldBe false
    }

    private fun greeterLoader(name: String): ExtensionClassLoader = ExtensionClassLoader(
        JarContents(
            mapOf(
                name.replace('.', '/') + ".class" to generatedClass(name, "hello"),
                "assets/data.json" to "{}".toByteArray(),
            ),
        ),
        "zoukan.extension.generated",
    )

    @Test
    fun `closing drops the bytes, and later loads fail cleanly`() {
        val name = "zoukan.extension.generated.Late"
        val loader = greeterLoader(name)
        val url = loader.getResource("assets/data.json")!!

        loader.close()

        loader.isClosed shouldBe true
        shouldThrow<ExtensionUnloadedException> { loader.loadClass(name) }.message shouldContain "uninstalled or updated"
        loader.getResource("assets/data.json") shouldBe null
        loader.getResourceAsStream("assets/data.json") shouldBe null
        // An address handed out before the close fails when opened
        shouldThrow<ExtensionUnloadedException> { url.openStream() }
        // Host classes still load
        loader.loadClass("kotlin.Unit") shouldBe Unit::class.java
    }

    @Test
    fun `a class defined before the close keeps working`() {
        val name = "zoukan.extension.generated.Early"
        val loader = greeterLoader(name)
        val cls = loader.loadClass(name)
        loader.close()
        cls.getMethod("greet").invoke(null) shouldBe "hello"
        loader.loadClass(name) shouldBe cls
    }

    /**
     * An extension class implementing Supplier whose get() creates [callee], a class it has not
     * loaded yet: old source code still running after its extension was updated or uninstalled.
     */
    private fun callerLoader(caller: String, callee: String): ExtensionClassLoader {
        val callerInternal = caller.replace('.', '/')
        val calleeInternal = callee.replace('.', '/')
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            callerInternal,
            null,
            "java/lang/Object",
            arrayOf("java/util/function/Supplier"),
        )
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, calleeInternal, "greet", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return ExtensionClassLoader(
            JarContents(
                mapOf(
                    "$callerInternal.class" to cw.toByteArray(),
                    "$calleeInternal.class" to generatedClass(callee, "hello"),
                ),
            ),
            "zoukan.extension.generated",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun ExtensionClassLoader.supplier(name: String): java.util.function.Supplier<Any?> =
        loadClass(name).getConstructor().newInstance() as java.util.function.Supplier<Any?>

    @Test
    fun `old code that needs a class after the close fails with an ordinary exception, not an Error`() {
        val loader = callerLoader("zoukan.extension.generated.Caller", "zoukan.extension.generated.Callee")
        val source = loader.supplier("zoukan.extension.generated.Caller")

        loader.close()

        // What the app's catch (e: Exception) blocks see; a NoClassDefFoundError would escape them
        val caught = try {
            source.get()
            null
        } catch (e: Exception) {
            e
        }
        caught.shouldBeInstanceOf<ExtensionUnloadedException>()
        caught.message shouldContain "reopen the screen"
        // Not remembered as a linkage failure: every later call fails the same ordinary way
        shouldThrow<ExtensionUnloadedException> { source.get() }
    }

    @Test
    fun `the same call before the close works`() {
        val loader = callerLoader("zoukan.extension.generated.Caller2", "zoukan.extension.generated.Callee2")
        loader.supplier("zoukan.extension.generated.Caller2").get() shouldBe "hello"
    }

    @Test
    fun `an interceptor of a closed extension fails an async OkHttp call through its callback`() {
        val loader = callerLoader("zoukan.extension.generated.Caller3", "zoukan.extension.generated.Callee3")
        val extensionCode = loader.supplier("zoukan.extension.generated.Caller3")
        loader.close()
        val uncaught = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                extensionCode.get()
                chain.proceed(chain.request())
            }
            .dispatcher(
                okhttp3.Dispatcher(
                    java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                        Thread(r).apply { setUncaughtExceptionHandler { _, e -> uncaught.set(e) } }
                    },
                ),
            )
            .build()
        val failure = java.util.concurrent.CompletableFuture<java.io.IOException>()
        client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:9/").build()).enqueue(
            object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    failure.complete(e)
                }
                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.close()
                    failure.completeExceptionally(AssertionError("expected a failure"))
                }
            },
        )
        failure.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<ExtensionUnloadedException>()
        client.dispatcher.executorService.shutdown()
        client.dispatcher.executorService.awaitTermination(10, TimeUnit.SECONDS)
        uncaught.get() shouldBe null
    }

    @Test
    fun `closing twice, or from several threads, is safe and runs the close once`() {
        val loader = greeterLoader("zoukan.extension.generated.Twice")
        val closes = java.util.concurrent.atomic.AtomicInteger()
        loader.onClose = { closes.incrementAndGet() }
        (1..8).map { Thread { loader.close() }.apply { start() } }.forEach { it.join() }
        loader.close()
        closes.get() shouldBe 1
        loader.isClosed shouldBe true
    }

    private fun apkOffer(): Extension.Available {
        val store = ExtensionStore(
            indexUrl = "https://store.example/index.pb",
            name = "store",
            badgeLabel = "store",
            signingKey = "key",
            contact = ExtensionStore.Contact("https://store.example", null),
            isLegacy = false,
            extensionListUrl = null,
        )
        return Extension.Available(
            name = "Sample", pkgName = "zoukan.extension.test.sample", versionName = "1.4.3", versionCode = 3,
            libVersion = 1.4, lang = "all", isNsfw = false, signatureHash = "key", storeName = "store",
            sources = emptyList(), apkUrl = "https://store.example/sample.apk", iconUrl = "", store = store,
            jarUrl = null,
        )
    }

    @Test
    fun `a failed conversion of the installed version keeps its apk and jar`() {
        val extensionsDir = File(dir, "extensions")
        val pkgDir = File(extensionsDir, "zoukan.extension.test.sample").apply { mkdirs() }
        val apk = File(pkgDir, "3.apk").apply { writeText("installed apk") }
        val jar = File(pkgDir, "3.jar").apply { writeText("installed jar") }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("new apk".toByteArray().toResponseBody()).build()
            }
            .build()
        val installer = DesktopExtensionInstaller(extensionsDir) { client }
        val manifest = ExtensionManifest(
            packageName = "zoukan.extension.test.sample",
            versionCode = 3,
            versionName = "1.4.3",
            label = "Sample",
            features = listOf(ExtensionManifest.EXTENSION_FEATURE),
            metaData = mapOf(ExtensionManifest.METADATA_SOURCE_CLASS to ".Sample"),
        )
        mockkObject(ExtensionSignatures, ExtensionManifest.Companion, ApkConverter) {
            every { ExtensionSignatures.verifyApk(any()) } returns listOf("key")
            every { ExtensionManifest.fromApk(any()) } returns manifest
            every { ApkConverter.convert(any(), any()) } throws
                ExtensionException(ExtensionException.Stage.CONVERT, "dex2jar failed")

            shouldThrow<ExtensionException> {
                runBlocking { installer.install(apkOffer(), forceApk = true, requiredSigner = "key", installedVersionCode = 3) }
            }.stage shouldBe ExtensionException.Stage.CONVERT
        }

        apk.readText() shouldBe "installed apk"
        jar.readText() shouldBe "installed jar"
        pkgDir.list()!!.sorted() shouldBe listOf("3.apk", "3.jar")
    }

    @Test
    fun `plain http stores are refused with a clear message, this computer excepted`() {
        shouldThrow<InsecureExtensionStoreException> { requireSecureStoreUrl("http://store.example/index.min.json") }
            .message shouldContain "must use https"
        requireSecureStoreUrl("https://store.example/index.min.json")
        requireSecureStoreUrl("http://127.0.0.1:8080/index.min.json")
    }

    @Test
    fun `a redirect that changes between http and https is not followed`() {
        // OkHttp's followSslRedirects covers both directions; a local http server can show it
        // without TLS. A real store's https-to-http redirect is refused by the same rule
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.responseHeaders.add("Location", "https://elsewhere.example/index.pb")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/index.pb"
            shouldThrow<InsecureExtensionStoreException> {
                runBlocking { OkHttpClient().awaitSecureStoreResponse(url) }
            }.message shouldContain "redirected"
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a redirect that stays on the same scheme is followed`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/old") { exchange ->
                exchange.responseHeaders.add("Location", "/new")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            createContext("/new") { exchange ->
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/old"
            runBlocking { OkHttpClient().awaitSecureStoreResponse(url) }.use { it.body.string() } shouldBe "ok"
        } finally {
            server.stop(0)
        }
    }
}
