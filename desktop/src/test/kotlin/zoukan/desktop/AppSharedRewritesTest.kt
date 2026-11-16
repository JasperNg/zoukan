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
                val parts = line.split(" | ")
                check(parts.size == 3) { "Bad rewrite line: $line" }
                Rewrite(parts[0].trim(), parts[1].trim(), parts[2].trim())
            }
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
