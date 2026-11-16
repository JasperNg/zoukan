package zoukan.desktop.harness

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import zoukan.desktop.ProjectOutputs
import java.io.File
import java.util.zip.ZipFile

/**
 * The app's own code (the desktop classes, the generated app-shared sources and the shared
 * modules) compiles against android-stubs, so an android.* call that android-compat does not
 * implement compiles and fails only when it runs: with "Stub!" or NoSuchMethodError, on a tracker
 * login, a sync or a gallery update. This runs the extension linkage check over those classes.
 *
 * A gap that is reviewed and unreachable on desktop goes in app-linkage-allowlist.txt, with its reason.
 *
 *   ./gradlew :desktop:wiringTest --tests '*AppLinkageTest'
 */
class AppLinkageTest {

    private val linkage = ApiLinkage(javaClass.classLoader)

    @Test
    fun `app code calls no Android API the compatibility layer lacks`() {
        val classes = appClasses()
        classes.size shouldBeGreaterThan 1_000
        // One string, so a failure prints every gap, not only the first
        gapsOf(classes).joinToString("\n") { "${it.symbol} (${it.reason})" } shouldBe ""
    }

    @Test
    fun `a stub call planted in app code is reported`() {
        val symbol = "android.os.ParcelFileDescriptor.open(Ljava/io/File;I)Landroid/os/ParcelFileDescriptor;"
        gapsOf(appClasses() + planted()).map { it.symbol } shouldContain symbol
    }

    /** The gaps in [classes] that the allowlist does not cover. */
    private fun gapsOf(classes: Map<String, ByteArray>): List<ApiLinkage.Gap> {
        val allowed = javaClass.getResourceAsStream("/app-linkage-allowlist.txt")!!.bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        // The classes checked are the app's own, so they are the app's copy, not a shadowed one
        return linkage.check(classes, reportShadowed = false)
            .filter { gap -> allowed.none { gap.symbol.startsWith(it) } }
    }

    /** `zoukan/desktop/Planted`, whose one method calls a method of android.jar that is a stub. */
    private fun planted(): Map<String, ByteArray> {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "zoukan/desktop/Planted", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null).apply {
            visitCode()
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "android/os/ParcelFileDescriptor",
                "open",
                "(Ljava/io/File;I)Landroid/os/ParcelFileDescriptor;",
                false,
            )
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return mapOf("zoukan/desktop/Planted" to writer.toByteArray())
    }

    /** Every class of the project's output on the class path, except the host the calls resolve against. */
    private fun appClasses(): Map<String, ByteArray> {
        val classes = HashMap<String, ByteArray>()
        ProjectOutputs.onClassPath().filterNot(::isHost).forEach { entry ->
            if (entry.isDirectory) {
                entry.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { file ->
                    classes[file.relativeTo(entry).path.replace('\\', '/').removeSuffix(".class")] = file.readBytes()
                }
            } else {
                ZipFile(entry).use { zip ->
                    zip.entries().asSequence().filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }.forEach {
                        classes[it.name.removeSuffix(".class")] = zip.getInputStream(it).use { s -> s.readBytes() }
                    }
                }
            }
        }
        return classes
    }

    /** android-compat and android-stubs implement the Android API; they are not callers of it. */
    private fun isHost(entry: File): Boolean = entry.absoluteFile.path.split(File.separatorChar)
        .any { it == "android-compat" || it == "android-stubs" }
}
