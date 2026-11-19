package zoukan.desktop

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import zoukan.desktop.manga.CustomCovers
import java.io.File

/** The class path entries that are the project's own output, as tests that scan compiled classes see them. */
object ProjectOutputs {

    /** The desktop app's classes and the jars of the project's own modules, not libraries. */
    fun isProjectOutput(file: File): Boolean {
        val root = File(System.getProperty("user.dir")).absoluteFile.parentFile
        val path = file.absoluteFile
        val p = path.invariantSeparatorsPath
        val testOutput = p.endsWith("/classes/kotlin/test") || p.endsWith("/classes/java/test") ||
            p.endsWith("/resources/test")
        return path.startsWith(root) && !testOutput && (path.isDirectory || path.name.endsWith(".jar"))
    }

    /** The project output on the test class path. */
    fun onClassPath(): List<File> = System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .map(::File)
        .filter(::isProjectOutput)
}

/**
 * The text rewrites the app-shared sync applies to upstream files must still match, and the
 * synced lookup table must not use Android's SparseArray stub. Gradle runs this with the
 * desktop folder as working directory.
 */
class AppSharedRewritesTest {

    private class Rewrite(val path: String, val from: String, val to: String)

    private fun rewrites(): List<Rewrite> {
        val list = File("app-shared-rewrites.txt")
        check(list.isFile) { "desktop/app-shared-rewrites.txt is missing" }
        return list.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split("|", limit = 3).map { it.trim() }
                check(parts.size == 3) { "Bad rewrite line: $line" }
                Rewrite(parts[0], parts[1], parts[2])
            }
    }

    @Test
    fun `no synced file uses the SparseArray stub`() {
        val root = File("build/generated/app-shared")
        check(root.isDirectory) { "build/generated/app-shared is missing" }
        val offenders = root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { "android.util.SparseArray" in it.readText() }
            .map { it.relativeTo(root).path }
            .toList()
        offenders shouldBe emptyList()
    }

    @Test
    fun `nothing extends the SparseArray stub`() {
        val methods = runCatching { Class.forName("androidx.core.util.AndroidxUtilCompat") }
            .getOrNull()?.methods.orEmpty()
            .filter { m -> m.parameterTypes.any { it.name == "android.util.SparseArray" } }
        methods.map { it.toString() } shouldBe emptyList()
    }

    @Test
    fun `every rewrite matches its upstream file`() {
        val all = rewrites()
        all.shouldNotBeEmpty()
        for (r in all) {
            File("../app/src/main/java/${r.path}").readText() shouldContain r.from
            val generated = File("build/generated/app-shared/${r.path}").readText()
            generated shouldContain r.to
            generated shouldNotContain r.from
        }
    }

    @Test
    fun `the synced lookup table does not use the SparseArray stub`() {
        val bytes = javaClass.classLoader.getResourceAsStream("exh/eh/MemAutoFlushingLookupTable.class")!!
            .use { it.readBytes() }
        val owners = mutableSetOf<String>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String?,
                        descriptor: String?,
                        isInterface: Boolean,
                    ) {
                        owners += owner
                    }

                    override fun visitTypeInsn(opcode: Int, type: String) {
                        owners += type
                    }

                    override fun visitFieldInsn(opcode: Int, owner: String, name: String?, descriptor: String?) {
                        owners += owner
                    }
                }
            },
            0,
        )
        owners.contains("android/util/SparseArray") shouldBe false
        owners.contains("zoukan/desktop/compat/SparseArray") shouldBe true
    }
}

/**
 * Runtime code does not depend on the UI package: only the entry points that start the UI
 * (main, the release self-test) may reach into it.
 */
class PackageDirectionTest {
    @Test
    fun `runtime classes do not reference the ui package`() {
        val root = File(ZoukanRuntime::class.java.protectionDomain.codeSource.location.toURI())
        val classes = root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
        classes.size shouldBeGreaterThan 500
        val ui = "zoukan/desktop/ui/".toByteArray(Charsets.US_ASCII)
        val offenders = classes
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it.startsWith("zoukan/desktop/") && !it.startsWith("zoukan/desktop/ui/") }
            .filterNot { path -> ALLOWED.any { path.startsWith(it) } }
            .filter { path -> File(root, path).readBytes().indexOf(ui) >= 0 }
        offenders.shouldBeEmpty()
    }

    @Test
    fun `a custom cover keeps its file name`(@TempDir dir: File) {
        CustomCovers.coverDir = File(dir, "covers")
        CustomCovers.file(42).relativeTo(dir).invariantSeparatorsPath shouldBe "covers/custom/a1d0c6e83f027327d8461063f4ac58a6"
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int =
        (0..size - needle.size).firstOrNull { i -> needle.indices.all { this[i + it] == needle[it] } } ?: -1

    private companion object {
        /** Entry points that start or drive the UI. */
        val ALLOWED = listOf("zoukan/desktop/MainKt", "zoukan/desktop/app/SelfTest")
    }
}
