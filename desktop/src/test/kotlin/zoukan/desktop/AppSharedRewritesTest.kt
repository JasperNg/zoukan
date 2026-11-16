package zoukan.desktop

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File

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
