package zoukan.desktop.harness

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

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
            visitMethodInsn(Opcodes.INVOKESTATIC, "ext/Probe", "run", "()V", false)
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
    fun `members inherited from the app through an extension class are checked`() {
        // ext/Receiver extends ResultReceiver (a stub) and calls the inherited send() on itself
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "ext/Receiver", null, "android/os/ResultReceiver", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.ACONST_NULL)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "ext/Receiver", "send", "(ILandroid/os/Bundle;)V", false)
            // Its own method is not the app's
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "ext/Receiver", "run", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        linkage.check(mapOf("ext/Receiver" to writer.toByteArray())).map { it.symbol } shouldContainExactlyInAnyOrder listOf(
            "android.os.ResultReceiver.send(ILandroid/os/Bundle;)V",
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

    /** A class loader that defines the host-side classes of a test, in front of the test's own. */
    private class HostLoader(parent: ClassLoader) : ClassLoader(parent) {
        private val defined = HashMap<String, ByteArray>()

        fun define(name: String, bytes: ByteArray): Class<*> {
            defined[name.replace('.', '/') + ".class"] = bytes
            return defineClass(name, bytes, 0, bytes.size)
        }

        /** The stub check reads the bytes of a host class this way. */
        override fun getResourceAsStream(name: String): java.io.InputStream? =
            defined[name]?.inputStream() ?: super.getResourceAsStream(name)
    }

    /** Host class `host/CompatActivity`, an app-side activity base class (as AppCompatActivity). */
    private fun hostCompatActivity(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "host/CompatActivity", null, "android/app/Activity", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "android/app/Activity", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** Host class `host/Messages` whose `describe()` returns the text "Stub!" and throws nothing. */
    private fun hostMessages(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "host/Messages", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "describe", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("Stub!")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `an activity extending an AppCompat-style host activity is activity-only`() {
        val host = HostLoader(javaClass.classLoader).also { it.define("host.CompatActivity", hostCompatActivity()) }
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "ext/DeepLink", null, "host/CompatActivity", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "open", "()V", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, "android/zoukan/Missing", "call", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        val gaps = ApiLinkage(host).check(mapOf("ext/DeepLink" to writer.toByteArray()))
        gaps.map { it.symbol to it.activityOnly } shouldContainExactlyInAnyOrder listOf("android.zoukan.Missing" to true)
    }

    @Test
    fun `an app method that only mentions Stub is not a stub`() {
        val host = HostLoader(javaClass.classLoader).also { it.define("host.Messages", hostMessages()) }
        ApiLinkage(host).check(probe(Triple("host/Messages", "describe", "()Ljava/lang/String;"))).shouldBeEmpty()
    }

    /** A host interface `host/Api` with the abstract method `run()V`. */
    private fun hostApi(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT, "host/Api", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "run", "()V", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** A host class `host/Hidden` with the private method `secret()V`. */
    private fun hostHidden(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "host/Hidden", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PRIVATE, "secret", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** `ext/Probe`, whose one method is built by [body]. */
    private fun probeWith(body: org.objectweb.asm.MethodVisitor.() -> Unit): Map<String, ByteArray> {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "ext/Probe", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null).apply {
            visitCode()
            body()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return mapOf("ext/Probe" to writer.toByteArray())
    }

    @Test
    fun `an abstract app method the extension does not implement is reported`() {
        val host = HostLoader(javaClass.classLoader).also { it.define("host.Api", hostApi()) }
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "ext/Impl", null, "java/lang/Object", arrayOf("host/Api"))
        writer.visitEnd()
        ApiLinkage(host).check(mapOf("ext/Impl" to writer.toByteArray())).map { it.symbol } shouldContainExactlyInAnyOrder listOf(
            "host.Api.run()V",
        )
    }

    @Test
    fun `a static call to an instance method is reported`() {
        val gaps = linkage.check(probeWith { visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "length", "()I", false) })
        gaps.map { it.symbol } shouldContainExactlyInAnyOrder listOf("java.lang.String.length()I")
        gaps.single().reason shouldContain "static"
    }

    @Test
    fun `a call to a private app method is reported`() {
        val host = HostLoader(javaClass.classLoader).also { it.define("host.Hidden", hostHidden()) }
        val gaps = ApiLinkage(host).check(
            probeWith {
                visitInsn(Opcodes.ACONST_NULL)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "host/Hidden", "secret", "()V", false)
            },
        )
        gaps.map { it.symbol } shouldContainExactlyInAnyOrder listOf("host.Hidden.secret()V")
        gaps.single().reason shouldContain "access"
    }

    @Test
    fun `a lambda of a missing interface is reported`() {
        val metafactory = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                "Ljava/lang/invoke/CallSite;",
            false,
        )
        val gaps = linkage.check(
            probeWith {
                visitInvokeDynamicInsn(
                    "run",
                    "()Landroid/zoukan/MissingFn;",
                    metafactory,
                    Type.getMethodType("()V"),
                    Handle(Opcodes.H_INVOKESTATIC, "ext/Probe", "run", "()V", false),
                    Type.getMethodType("()V"),
                )
                visitInsn(Opcodes.POP)
            },
        )
        gaps.map { it.symbol to it.reason } shouldContainExactlyInAnyOrder listOf("android.zoukan.MissingFn" to "class not found")
    }

    @Test
    fun `an extension class the app shadows is reported`() {
        val bytes = javaClass.classLoader.getResourceAsStream("kotlin/Unit.class")!!.use { it.readBytes() }
        val gaps = linkage.check(mapOf("kotlin/Unit" to bytes))
        gaps.map { it.symbol } shouldContainExactlyInAnyOrder listOf("kotlin.Unit")
        gaps.single().reason shouldContain "app's copy"
    }
}
