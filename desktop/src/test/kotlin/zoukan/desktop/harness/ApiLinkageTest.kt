package zoukan.desktop.harness

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ApiLinkageTest {

    private val linkage = ApiLinkage(javaClass.classLoader)

    /** A class `ext/Probe` whose one method makes [calls] (owner, name, descriptor, isStatic). */
    private fun probe(vararg calls: Triple<String, String, String>, field: Pair<String, String>? = null): Map<String, ByteArray> {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "ext/Probe", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null).apply {
            visitCode()
            calls.forEach { (owner, name, desc) -> visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, desc, false) }
            field?.let { (owner, name) -> visitFieldInsn(Opcodes.GETSTATIC, owner, name, "I") }
            // The extension's own classes are not checked
            visitMethodInsn(Opcodes.INVOKESTATIC, "ext/Probe", "missing", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return mapOf("ext/Probe" to writer.toByteArray())
    }

    @Test
    fun `references the app provides resolve`() {
        linkage.check(
            probe(
                Triple("java/lang/Integer", "parseInt", "(Ljava/lang/String;)I"),
                Triple("android/text/TextUtils", "isEmpty", "(Ljava/lang/CharSequence;)Z"),
                field = "java/lang/Integer" to "MAX_VALUE",
            ),
        ).shouldBeEmpty()
    }

    @Test
    fun `missing classes, methods and fields are reported`() {
        linkage.check(
            probe(
                Triple("android/zoukan/NoSuchClass", "call", "()V"),
                Triple("java/lang/Integer", "noSuchMethod", "()V"),
                field = "java/lang/Integer" to "NO_SUCH_FIELD",
            ),
        ).map { it.symbol } shouldContainExactlyInAnyOrder listOf(
            "android.zoukan.NoSuchClass",
            "java.lang.Integer.noSuchMethod()V",
            "java.lang.Integer.NO_SUCH_FIELD:I",
        )
    }

    @Test
    fun `stubs of android jar are reported`() {
        // android.jar's ParcelFileDescriptor is a stub the compatibility layer does not replace
        linkage.check(probe(Triple("android/os/ParcelFileDescriptor", "open", "(Ljava/io/File;I)Landroid/os/ParcelFileDescriptor;")))
            .map { it.symbol to it.reason.substringBefore(':') } shouldContainExactlyInAnyOrder listOf(
            "android.os.ParcelFileDescriptor.open(Ljava/io/File;I)Landroid/os/ParcelFileDescriptor;" to "stub",
        )
    }
}
