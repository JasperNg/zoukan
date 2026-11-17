package zoukan.desktop.e2e

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.objectweb.asm.ClassReader
import zoukan.desktop.e2e.extjar.StoreFixtureSource
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * An extension store on the [FixtureServer], in Keiyoushi's v2 format (`index.min.json` with the
 * store's signing key and each extension's `jarUrl`), with real JAR-signed extension jars built at
 * test time, so a journey goes through the app's whole store, install and load path.
 *
 * Each jar holds [StoreFixtureSource]'s compiled classes, moved to [JAR_PACKAGE] so the class
 * exists only in the jar (the extension class loader asks the app's class path first), a text
 * AndroidManifest.xml as Keiyoushi's jars carry, and the source's config file. Jars are signed with
 * jarsigner using throwaway keys made by keytool, as a store signs its jars.
 *
 * Served at `/store/index.min.json` and `/store/<package>.jar`.
 */
class FixtureStore(private val server: FixtureServer) : AutoCloseable {

    /** A throwaway signing key, and the SHA-256 of its certificate as the app computes it. */
    inner class Key(alias: String) {
        val keystore: File = File(dir, "$alias.p12")
        val alias = alias
        val fingerprint: String

        init {
            run(
                jdkTool("keytool"), "-genkeypair", "-keystore", keystore.path, "-storetype", "PKCS12",
                "-storepass", PASSWORD, "-keypass", PASSWORD, "-alias", alias, "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "365", "-dname", "CN=Zoukan fixture $alias",
            )
            val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, PASSWORD.toCharArray()) } }
            fingerprint = MessageDigest.getInstance("SHA-256").digest(ks.getCertificate(alias).encoded)
                .joinToString("") { "%02x".format(it) }
        }
    }

    /** An extension the store offers: its package, name (also its source's name) and signer. */
    data class Offer(val pkgName: String, val name: String, val key: Key, val versionCode: Long = 1)

    private val dir: File = Files.createTempDirectory("zoukan-fixture-store").toFile()

    /** The key the store's index names: extensions signed with it are trusted. */
    val storeKey = Key("store")

    /** Another key, which no added store names. */
    val otherKey = Key("other")

    val trusted = Offer(TRUSTED_PKG, TRUSTED_NAME, storeKey)
    val rogue = Offer(ROGUE_PKG, ROGUE_NAME, otherKey)
    val offers = listOf(trusted, rogue)

    val indexUrl: String get() = "${server.baseUrl}/store/index.min.json"

    fun jarPath(offer: Offer) = "/store/${offer.pkgName}.jar"

    private val jars: Map<String, File> = offers.associate { "${it.pkgName}.jar" to buildJar(it) }

    private val route: (HttpExchange, List<String>) -> Boolean = route@{ exchange, parts ->
        if (parts.size != 2 || parts[0] != "store") return@route false
        when (val file = parts[1]) {
            "index.min.json" -> send(exchange, "application/json", indexJson().toByteArray())
            in jars -> send(exchange, "application/java-archive", jars.getValue(file).readBytes())
            else -> return@route false
        }
        true
    }

    init {
        server.extraRoutes += route
    }

    private fun indexJson(): String = buildJsonObject {
        put("name", STORE_NAME)
        put("badgeLabel", STORE_BADGE)
        put("signingKey", storeKey.fingerprint)
        put(
            "contact",
            buildJsonObject {
                put("website", server.baseUrl)
                put("discord", JsonNull)
            },
        )
        put(
            "extensionList",
            buildJsonObject {
                put(
                    "extensions",
                    buildJsonArray {
                        offers.forEach { offer ->
                            add(
                                buildJsonObject {
                                    put("name", offer.name)
                                    put("packageName", offer.pkgName)
                                    put(
                                        "resources",
                                        buildJsonObject {
                                            put("apkUrl", "${server.baseUrl}/store/${offer.pkgName}.apk")
                                            put("iconUrl", "${server.baseUrl}/images/cover-1.png")
                                            put("jarUrl", server.baseUrl + jarPath(offer))
                                        },
                                    )
                                    put("extensionLib", "1.4")
                                    put("versionCode", offer.versionCode)
                                    put("versionName", "1.4.${offer.versionCode}")
                                    put("contentWarning", "SAFE")
                                    put(
                                        "sources",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("id", sourceId(offer.name))
                                                    put("name", offer.name)
                                                    put("language", "en")
                                                    put("homeUrl", server.baseUrl)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            },
        )
        put("extensionListUrl", JsonNull)
    }.toString()

    private fun buildJar(offer: Offer): File {
        val jar = File(dir, "${offer.pkgName}.jar")
        ZipOutputStream(jar.outputStream()).use { out ->
            fun entry(name: String, bytes: ByteArray) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
            entry("AndroidManifest.xml", manifest(offer).toByteArray())
            entry(CONFIG_FILE, "name=${offer.name}\nbaseUrl=${server.baseUrl}\n".toByteArray())
            sourceClasses().forEach { (name, bytes) -> entry(name, bytes) }
        }
        run(jdkTool("jarsigner"), "-keystore", offer.key.keystore.path, "-storepass", PASSWORD, jar.path, offer.key.alias)
        return jar
    }

    private fun manifest(offer: Offer) = """
        <manifest package="${offer.pkgName}" android:versionCode="${offer.versionCode}" android:versionName="1.4.${offer.versionCode}"
            xmlns:android="http://schemas.android.com/apk/res/android">
          <uses-feature android:name="tachiyomi.extension" />
          <application android:label="Tachiyomi: ${offer.name}">
            <meta-data android:name="tachiyomi.extension.class" android:value="$JAR_SOURCE_CLASS" />
            <meta-data android:name="tachiyomix.extensionLib" android:value="1.4" />
            <meta-data android:name="tachiyomi.extension.nsfw" android:value="0" />
          </application>
        </manifest>
    """.trimIndent()

    /**
     * The compiled classes of [StoreFixtureSource]'s package, renamed to [JAR_PACKAGE]: the names
     * have the same length, so replacing them in the class files' UTF-8 constants keeps every
     * class file valid. Class names, descriptors and the Kotlin metadata all change together.
     */
    private fun sourceClasses(): Map<String, ByteArray> {
        val self = checkNotNull(ClassLoader.getSystemResource(TEST_PATH + "/StoreFixtureSource.class")) {
            "StoreFixtureSource.class is not on the test class path"
        }
        val folder = File(self.toURI()).parentFile
        val classes = folder.listFiles { f -> f.isFile && f.name.endsWith(".class") }.orEmpty()
        check(classes.isNotEmpty())
        return classes.associate { file ->
            val bytes = relocate(file.readBytes())
            val name = ClassReader(bytes).className
            check(name.startsWith(JAR_PATH + "/")) { "${file.name} was not relocated: $name" }
            "$name.class" to bytes
        }
    }

    private fun relocate(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        listOf(TEST_PATH to JAR_PATH, TEST_PACKAGE to JAR_PACKAGE).forEach { (from, to) ->
            val a = from.toByteArray()
            val b = to.toByteArray()
            check(a.size == b.size)
            var i = 0
            while (i <= out.size - a.size) {
                if ((a.indices).all { out[i + it] == a[it] }) {
                    b.copyInto(out, i)
                    i += a.size
                } else {
                    i++
                }
            }
        }
        return out
    }

    private fun send(exchange: HttpExchange, type: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.extraRoutes -= route
        dir.deleteRecursively()
    }

    private fun jdkTool(name: String): String = File(System.getProperty("java.home"), "bin/$name").path

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "${File(command.first()).name}: $output" }
    }

    companion object {
        const val STORE_NAME = "Zoukan Fixture Store"
        const val STORE_BADGE = "ZFS"

        const val TRUSTED_PKG = "zoukan.fixture.ext.trusted"
        const val TRUSTED_NAME = "Zoukan Store Fixtures"
        const val ROGUE_PKG = "zoukan.fixture.ext.rogue"
        const val ROGUE_NAME = "Zoukan Rogue Fixtures"

        /** Where [StoreFixtureSource] is compiled, and where the jar has it: names of the same length. */
        const val TEST_PACKAGE = "zoukan.desktop.e2e.extjar"
        const val JAR_PACKAGE = "zoukan.storefx.e2e.extjar"
        private val TEST_PATH = TEST_PACKAGE.replace('.', '/')
        private val JAR_PATH = JAR_PACKAGE.replace('.', '/')
        const val JAR_SOURCE_CLASS = "$JAR_PACKAGE.StoreFixtureSource"

        private const val CONFIG_FILE = zoukan.desktop.e2e.extjar.CONFIG

        /**
         * The id the extension's source has once loaded (HttpSource.generateId for [name], "en"
         * and version 1), so the index lists the id the installed source really has, as a store does.
         */
        fun sourceId(name: String): Long {
            val bytes = MessageDigest.getInstance("MD5").digest("${name.lowercase()}/en/1".toByteArray())
            return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
        }
        private const val PASSWORD = "password"
    }
}
