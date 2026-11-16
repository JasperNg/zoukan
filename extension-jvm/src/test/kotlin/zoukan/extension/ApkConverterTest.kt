package zoukan.extension

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import kotlinx.coroutines.runBlocking
import mihon.domain.extension.model.ExtensionStore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An APK is read once: the bytes that were verified are the bytes that are parsed, converted and stored. */
class ApkConverterTest {
    @TempDir
    lateinit var dir: File

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
    fun `conversion uses the verified bytes`() {
        val extensionsDir = File(dir, "extensions")
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("genuine apk".toByteArray().toResponseBody()).build()
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
        var converted: ByteArray? = null
        mockkObject(ExtensionSignatures, ExtensionManifest.Companion, ApkConverter) {
            every { ExtensionSignatures.verifyApk(any()) } answers {
                // A process of the same user swaps the downloaded file once the check has read it
                extensionsDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".apk") }
                    .forEach { it.writeText("attacker apk") }
                listOf("key")
            }
            every { ExtensionManifest.fromApk(any()) } returns manifest
            every { ApkConverter.convert(any(), any()) } answers {
                converted = firstArg<ByteArray>()
                ZipOutputStream(secondArg<File>().outputStream()).use { out ->
                    out.putNextEntry(ZipEntry("AndroidManifest.xml"))
                    out.write("<manifest/>".toByteArray())
                    out.closeEntry()
                }
            }

            runBlocking { installer.install(apkOffer(), forceApk = true) }
        }

        converted?.decodeToString() shouldBe "genuine apk"
        File(extensionsDir, "zoukan.extension.test.sample/3.apk").readText() shouldBe "genuine apk"
    }
}
