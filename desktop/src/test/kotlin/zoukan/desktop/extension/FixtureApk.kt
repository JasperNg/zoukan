package zoukan.desktop.extension

import com.android.apksig.ApkSigner
import zoukan.desktop.e2e.FixtureStore
import zoukan.desktop.testing.FileSystemSandbox
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
