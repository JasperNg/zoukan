package zoukan.extension

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A factory a test extension jar can name; the class loader finds it on the host class path. */
class TestExtensionFactory : SourceFactory {
    override fun createSources(): List<Source> = emptyList()
}

/**
 * The hash gate for jars converted from an APK: the jar itself is unsigned and user-writable, so a
 * jar that does not match its recorded hash is converted again from the verified APK instead of
 * being loaded, and only an intact one loads as it is.
 */
class DesktopExtensionLoaderTest {
    private val pkgName = "zoukan.extension.test.sample"
    private val sourceClass = "zoukan.extension.TestExtensionFactory"
    private val evilPkgName = "zoukan.extension.test.evil"
    private val evilSourceClass = "zoukan.extension.test.EvilFactory"

    @TempDir
    lateinit var extensionsDir: File

    /** The text manifest a converted jar carries, read by ExtensionManifest. */
    private fun manifest(pkgName: String, sourceClass: String) = """
        <manifest package="$pkgName" android:versionCode="3" android:versionName="1.4.3"
            xmlns:android="http://schemas.android.com/apk/res/android">
          <uses-feature android:name="tachiyomi.extension" />
          <application android:label="Sample">
            <meta-data android:name="tachiyomi.extension.class" android:value="$sourceClass" />
            <meta-data android:name="tachiyomix.extensionLib" android:value="1.4" />
          </application>
        </manifest>
    """.trimIndent()

    private fun writeJar(jar: File, manifest: String) {
        ZipOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("AndroidManifest.xml"))
            out.write(manifest.toByteArray())
            out.closeEntry()
        }
    }

    /** An APK-method install of version 3, as if [jarManifest] is what the conversion produced. */
    private fun install(jarManifest: String): DesktopExtensionLoader.InstalledFiles {
        val dir = File(extensionsDir, pkgName).apply { mkdirs() }
        val apk = File(dir, "3.apk").apply { writeText("apk bytes") }
        val jar = File(dir, "3.jar")
        writeJar(jar, jarManifest)
        return DesktopExtensionLoader.InstalledFiles(pkgName, 3, jar, apk)
    }

    private fun loader() = DesktopExtensionLoader(
        extensionsDir,
        mockk<ExtensionTrust> { every { isTrusted(any(), any(), any(), any()) } returns true },
    )

    /** The APK is signature-verified; only dex2jar is stubbed, since tests have no dex code. */
    private fun withVerifiedApk(files: DesktopExtensionLoader.InstalledFiles, block: () -> Unit) {
        val apk = files.apk!!
        mockkObject(ExtensionSignatures) {
            every { ExtensionSignatures.verifyApk(apk) } returns listOf("store-key")
            mockkObject(ApkConverter) { block() }
        }
    }

    @Test
    fun `a converted jar that was swapped is converted again from the verified apk`() {
        val goodManifest = manifest(pkgName, sourceClass)
        val files = install(goodManifest)
        ApkConverter.recordHash(files.jar)
        // A same-user process replaces the jar; its manifest even names another package
        writeJar(files.jar, manifest(evilPkgName, evilSourceClass))
        val apk = files.apk!!
        val jar = files.jar
        withVerifiedApk(files) {
            every { ApkConverter.convert(apk, jar) } answers { writeJar(jar, goodManifest) }

            val result = loader().load(files, emptyList())

            // The tampered manifest is not the one that loaded: the healed jar's is
            result.shouldBeInstanceOf<LoadResult.Success>().extension.pkgName shouldBe pkgName
            verify(exactly = 1) { ApkConverter.convert(apk, jar) }
            ApkConverter.recordedHash(jar) shouldBe ApkConverter.sha256Of(jar)
        }
    }

    @Test
    fun `a converted jar that matches its recorded hash loads without re-conversion`() {
        val files = install(manifest(pkgName, sourceClass))
        ApkConverter.recordHash(files.jar)
        withVerifiedApk(files) {
            every { ApkConverter.convert(any(), any()) } just runs

            val result = loader().load(files, emptyList())

            result.shouldBeInstanceOf<LoadResult.Success>()
            verify(exactly = 0) { ApkConverter.convert(any(), any()) }
        }
    }

    @Test
    fun `a legacy install without a recorded hash is converted again before it loads`() {
        // Installed before the hash was recorded: the existing jar is not blessed as it is
        val files = install(manifest(pkgName, sourceClass))
        val apk = files.apk!!
        val jar = files.jar
        withVerifiedApk(files) {
            every { ApkConverter.convert(apk, jar) } just runs

            val result = loader().load(files, emptyList())

            result.shouldBeInstanceOf<LoadResult.Success>()
            verify(exactly = 1) { ApkConverter.convert(apk, jar) }
            ApkConverter.recordedHash(jar) shouldBe ApkConverter.sha256Of(jar)
        }
    }

    @Test
    fun `a failed re-conversion fails the load`() {
        val files = install(manifest(pkgName, sourceClass))
        ApkConverter.recordHash(files.jar)
        writeJar(files.jar, manifest(evilPkgName, evilSourceClass))
        withVerifiedApk(files) {
            every { ApkConverter.convert(any(), any()) } throws
                ExtensionException(ExtensionException.Stage.CONVERT, "dex2jar failed")

            val result = loader().load(files, emptyList())

            result.shouldBeInstanceOf<LoadResult.Error>().error.stage shouldBe ExtensionException.Stage.CONVERT
        }
    }
}
