package zoukan.extension

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import suwayomi.tachidesk.manga.impl.util.BytecodeEditor
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A store's signed jar gets the same Android-behaviour rewrite (java.text.SimpleDateFormat to
 * AndroidCompat's ICU-backed copy) that an APK gets at conversion, applied when the class loader
 * defines each class, so the signature is still checked on the bytes as they were signed. And the
 * installed extensions are known to the emulated package manager.
 */
class JarAndroidRewriteTest {
    @TempDir
    lateinit var dir: File

    private val replacement = "xyz.nulldev.androidcompat.replace.java.text.SimpleDateFormat"

    /** An extension class with a SimpleDateFormat field, and make() that creates one, as extensions do. */
    private fun dateFormatClass(name: String): ByteArray {
        val internal = name.replace('.', '/')
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "format", "Ljava/text/SimpleDateFormat;", null, null)
            .visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "make", "()Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/text/SimpleDateFormat")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("yyyy-MM-dd")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/text/SimpleDateFormat", "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.DUP)
            visitFieldInsn(Opcodes.PUTSTATIC, internal, "format", "Ljava/text/SimpleDateFormat;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

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

    private fun sign(jar: File) {
        val keystore = File(dir, "test.p12")
        run(
            jdkTool("keytool"), "-genkeypair", "-keystore", keystore.path, "-storetype", "PKCS12",
            "-storepass", "password", "-keypass", "password", "-alias", "store", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "365", "-dname", "CN=Test store",
        )
        run(jdkTool("jarsigner"), "-keystore", keystore.path, "-storepass", "password", jar.path, "store")
    }

    @Test
    fun `a signed jar verifies on its own bytes and its classes get the Android SimpleDateFormat`() {
        val name = "zoukan.extension.generated.Dates"
        val path = name.replace('.', '/') + ".class"
        val original = dateFormatClass(name)
        val jar = File(dir, "signed.jar")
        writeZip(jar, mapOf(path to original, "AndroidManifest.xml" to "<manifest/>".toByteArray()))
        sign(jar)

        val verified = ExtensionSignatures.verifyJar(jar)
        // What was verified is what was signed: the rewrite happens later, in the class loader
        verified.contents[path]!!.contentEquals(original) shouldBe true

        val cls = ExtensionClassLoader(verified.contents, "zoukan.extension.generated").loadClass(name)
        cls.getMethod("make").invoke(null).javaClass.name shouldBe replacement
        cls.getField("format").type.name shouldBe replacement
    }

    @Test
    fun `the rewrite is idempotent, so a class converted from an APK loads unchanged`() {
        // Rewritten at conversion; the class loader rewrites again, which must not prefix twice
        val once = BytecodeEditor.fixAndroidClass(dateFormatClass("zoukan.extension.generated.Twice"))
        val twice = BytecodeEditor.fixAndroidClass(once)
        String(twice, Charsets.ISO_8859_1).contains("replace/xyz/nulldev") shouldBe false

        val cls = ExtensionClassLoader(
            JarContents(mapOf("zoukan/extension/generated/Twice.class" to twice)),
            "zoukan.extension.generated",
        ).loadClass("zoukan.extension.generated.Twice")
        cls.getField("format").type.name shouldBe replacement
        cls.getMethod("make").invoke(null).javaClass.name shouldBe replacement
    }

    @Test
    fun `a class without SimpleDateFormat is defined from its own bytes`() {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "zoukan/extension/generated/Plain", null, "java/lang/Object", null)
        cw.visitEnd()
        val bytes = cw.toByteArray()
        BytecodeEditor.fixAndroidClass(bytes) shouldBeSameInstanceAs bytes
    }

    private fun manifest(pkgName: String, versionCode: Long) = """
        <manifest package="$pkgName" android:versionCode="$versionCode" android:versionName="1.4.$versionCode"
            xmlns:android="http://schemas.android.com/apk/res/android">
          <uses-feature android:name="tachiyomi.extension" />
          <application android:label="Tachiyomi: Sample">
            <meta-data android:name="tachiyomi.extension.class" android:value=".Sample" />
          </application>
        </manifest>
    """.trimIndent()

    @Test
    fun `the package manager finds installed extensions in the extensions folder`() {
        val pkg = "eu.kanade.tachiyomi.extension.en.sample"
        val pkgDir = File(dir, pkg).apply { mkdirs() }
        writeZip(File(pkgDir, "3.jar"), mapOf("AndroidManifest.xml" to manifest(pkg, 3).toByteArray()))
        writeZip(File(pkgDir, "12.jar"), mapOf("AndroidManifest.xml" to manifest(pkg, 12).toByteArray()))
        val packages = InstalledExtensionPackages(dir)

        val info = packages.find(pkg).shouldNotBeNull()
        info.packageName shouldBe pkg
        // The newest version, by number, not by name
        info.versionName shouldBe "1.4.12"
        info.longVersionCode shouldBe 12L
        info.applicationInfo.packageName shouldBe pkg
        info.applicationInfo.metaData.getString("tachiyomi.extension.class") shouldBe ".Sample"
        packages.all().map { it.packageName } shouldBe listOf(pkg)

        packages.find("eu.kanade.tachiyomi.extension.en.missing").shouldBeNull()
        packages.find("..").shouldBeNull()

        // Uninstalled: gone at once
        pkgDir.deleteRecursively()
        packages.find(pkg).shouldBeNull()
    }
}
