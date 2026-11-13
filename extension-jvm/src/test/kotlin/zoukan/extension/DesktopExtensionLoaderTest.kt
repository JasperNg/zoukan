package zoukan.extension

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import io.kotest.assertions.throwables.shouldThrow
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
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A factory a test extension jar can name; the class loader finds it on the host class path. */
class TestExtensionFactory : SourceFactory {
    override fun createSources(): List<Source> = emptyList()
}

/** Hashes kept in memory, standing in for the app's encrypted preferences. */
class FakeConvertedJarHashes : ConvertedJarHashes {
    val map = mutableMapOf<String, String>()
    override fun matches(pkgName: String, versionCode: Long, sha256: String) = map[pkgName] == "$versionCode:$sha256"
    override fun record(pkgName: String, versionCode: Long, sha256: String) {
        map[pkgName] = "$versionCode:$sha256"
    }
    override fun forget(pkgName: String) {
        map -= pkgName
    }
}

/**
 * The hash gate for jars converted from an APK: the jar itself is unsigned and user-writable, so a
 * jar that does not match the hash recorded outside the extensions folder is converted again from
 * the verified APK instead of being loaded, and only an intact one loads as it is.
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

    private fun writeJar(jar: File, manifest: String, classes: Map<String, ByteArray> = emptyMap()) {
        ZipOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("AndroidManifest.xml"))
            out.write(manifest.toByteArray())
            out.closeEntry()
            classes.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name.replace('.', '/') + ".class"))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    /** A SourceFactory with no sources that exists only in the jar, not on the test class path. */
    private fun generatedFactory(name: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            name.replace('.', '/'),
            null,
            "java/lang/Object",
            arrayOf(SourceFactory::class.java.name.replace('.', '/')),
        )
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "createSources", "()Ljava/util/List;", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** An empty class that exists only in the jar. */
    private fun generatedClass(name: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** An APK-method install of version 3, as if [jarManifest] is what the conversion produced. */
    private fun install(jarManifest: String): DesktopExtensionLoader.InstalledFiles {
        val dir = File(extensionsDir, pkgName).apply { mkdirs() }
        val apk = File(dir, "3.apk").apply { writeText("apk bytes") }
        val jar = File(dir, "3.jar")
        writeJar(jar, jarManifest)
        return DesktopExtensionLoader.InstalledFiles(pkgName, 3, jar, apk)
    }

    private val hashes = FakeConvertedJarHashes()

    private fun loader() = DesktopExtensionLoader(
        extensionsDir,
        mockk<ExtensionTrust> { every { isTrusted(any(), any(), any(), any()) } returns true },
        hashes,
    )

    private fun recordHash(files: DesktopExtensionLoader.InstalledFiles) =
        hashes.record(files.pkgName, files.versionCode, ApkConverter.sha256Of(files.jar))

    private fun recordedHash(files: DesktopExtensionLoader.InstalledFiles) = hashes.map[files.pkgName]

    private fun expectedHash(files: DesktopExtensionLoader.InstalledFiles) =
        "${files.versionCode}:${ApkConverter.sha256Of(files.jar)}"

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
        recordHash(files)
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
            recordedHash(files) shouldBe expectedHash(files)
        }
    }

    @Test
    fun `a converted jar that matches its recorded hash loads without re-conversion`() {
        val files = install(manifest(pkgName, sourceClass))
        recordHash(files)
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
            recordedHash(files) shouldBe expectedHash(files)
        }
    }

    @Test
    fun `a hash sidecar next to the jar is not trusted`() {
        // How earlier versions recorded the hash: a same-user process can write it as easily as
        // the jar, so a swapped jar with a matching sidecar still converts again
        val goodManifest = manifest(pkgName, sourceClass)
        val files = install(goodManifest)
        writeJar(files.jar, manifest(evilPkgName, evilSourceClass))
        val sidecar = File(files.jar.parentFile, "3.sha256").apply { writeText(ApkConverter.sha256Of(files.jar)) }
        val apk = files.apk!!
        val jar = files.jar
        withVerifiedApk(files) {
            every { ApkConverter.convert(apk, jar) } answers { writeJar(jar, goodManifest) }

            loader().load(files, emptyList()).shouldBeInstanceOf<LoadResult.Success>().extension.pkgName shouldBe pkgName

            verify(exactly = 1) { ApkConverter.convert(apk, jar) }
            sidecar.exists() shouldBe false
        }
    }

    @Test
    fun `the hash the installer hands over is recorded and loads without re-conversion`() {
        val installed = install(manifest(pkgName, sourceClass))
        val files = installed.copy(convertedSha256 = ApkConverter.sha256Of(installed.jar))
        withVerifiedApk(files) {
            every { ApkConverter.convert(any(), any()) } just runs

            loader().load(files, emptyList()).shouldBeInstanceOf<LoadResult.Success>()

            verify(exactly = 0) { ApkConverter.convert(any(), any()) }
            recordedHash(files) shouldBe expectedHash(files)
        }
    }

    @Test
    fun `a loaded extension runs from memory, so its jar can be deleted`() {
        val factory = "zoukan.extension.generated.JarOnlyFactory"
        val later = "zoukan.extension.generated.LoadedLater"
        val afterClose = "zoukan.extension.generated.AfterClose"
        val files = install(manifest(pkgName, factory))
        writeJar(
            files.jar,
            manifest(pkgName, factory),
            mapOf(factory to generatedFactory(factory), later to generatedClass(later), afterClose to generatedClass(afterClose)),
        )
        recordHash(files)
        // Only the jar has these classes
        shouldThrow<ClassNotFoundException> { Class.forName(factory) }
        withVerifiedApk(files) {
            val result = loader().load(files, emptyList()).shouldBeInstanceOf<LoadResult.Success>()
            val classLoader = result.classLoader!!
            // Nothing holds the jar open (on Windows an open jar cannot be deleted)
            files.jar.delete() shouldBe true
            files.jar.exists() shouldBe false

            // The factory came from the jar's bytes, and a class not loaded yet still comes from
            // the bytes read at load, not from the deleted file
            Class.forName(factory, false, classLoader).classLoader shouldBe classLoader
            classLoader.loadClass(later).classLoader shouldBe classLoader

            classLoader.close()
            classLoader.isClosed shouldBe true
            shouldThrow<ExtensionUnloadedException> { classLoader.loadClass(afterClose) }
        }
    }

    @Test
    fun `an update that fails to load keeps the working version's hash`() {
        val working = install(manifest(pkgName, sourceClass))
        recordHash(working)
        val workingHash = expectedHash(working)
        // Version 4 converts fine but names a source class that does not exist
        val dir = working.jar.parentFile
        val jar4 = File(dir, "4.jar").also { writeJar(it, manifest(pkgName, "zoukan.extension.test.Missing")) }
        val apk4 = File(dir, "4.apk").apply { writeText("apk 4 bytes") }
        val update = DesktopExtensionLoader.InstalledFiles(pkgName, 4, jar4, apk4, convertedSha256 = ApkConverter.sha256Of(jar4))
        mockkObject(ExtensionSignatures) {
            every { ExtensionSignatures.verifyApk(apk4) } returns listOf("store-key")

            loader().load(update, emptyList()).shouldBeInstanceOf<LoadResult.Error>()

            recordedHash(working) shouldBe workingHash
        }
        // So the working version still loads without converting again
        withVerifiedApk(working) {
            every { ApkConverter.convert(any(), any()) } just runs
            loader().load(working, emptyList()).shouldBeInstanceOf<LoadResult.Success>()
            verify(exactly = 0) { ApkConverter.convert(any(), any()) }
        }
    }

    @Test
    fun `a failed re-conversion fails the load`() {
        val files = install(manifest(pkgName, sourceClass))
        recordHash(files)
        writeJar(files.jar, manifest(evilPkgName, evilSourceClass))
        withVerifiedApk(files) {
            every { ApkConverter.convert(any(), any()) } throws
                ExtensionException(ExtensionException.Stage.CONVERT, "dex2jar failed")

            val result = loader().load(files, emptyList())

            result.shouldBeInstanceOf<LoadResult.Error>().error.stage shouldBe ExtensionException.Stage.CONVERT
        }
    }
}
