package zoukan.desktop.harness

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.reflect.Modifier
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the classes, methods and fields an extension's bytecode uses that the desktop app does not
 * provide: the Android API the compatibility layer lacks, a Kotlin or OkHttp API newer than the
 * app's, and the like. Each such call would fail with NoClassDefFoundError, NoSuchMethodError or
 * NoSuchFieldError when it runs, which may be only on some code path (a setting, a login, one
 * site's quirk), so it is checked statically, without running the extension.
 *
 * Only references to classes outside the extension jar are checked; they resolve against
 * [host], the app's class loader, as the extension's class loader resolves them. Results are
 * cached across extensions, so a check of every extension in a store stays fast.
 */
class ApiLinkage(private val host: ClassLoader) {

    /**
     * A reference that does not resolve. [symbol] is `owner`, `owner.name(desc)` or `owner.name:desc`.
     * [activityOnly]: only an Activity of the extension uses it (the deep-link activity Android
     * opens for a site's URL), which the desktop never starts.
     */
    data class Gap(val symbol: String, val reason: String, val activityOnly: Boolean = false)

    private val classCache = ConcurrentHashMap<String, Result<Class<*>>>()
    // Optional, since a ConcurrentHashMap keeps no nulls and most members resolve
    private val memberCache = ConcurrentHashMap<String, Optional<Gap>>()

    /** The unresolved references of the classes in [classes] (internal name to bytes), distinct and sorted. */
    fun check(classes: Map<String, ByteArray>): List<Gap> {
        val jar = Jar(classes.keys)
        // Each reference, with the classes that make it
        val refs = LinkedHashMap<Ref, MutableSet<String>>()
        classes.forEach { (name, bytes) -> collect(name, bytes, refs, jar) }
        val activities = jar.own.filterTo(HashSet()) { isActivity(it, jar.supers) }
        // Lambdas and inner classes of an activity run only in it
        fun inActivity(caller: String) = caller in activities || activities.any { caller.startsWith("$it$") }
        return refs.asSequence()
            .mapNotNull { (ref, callers) -> resolveFrom(ref, jar)?.let { it to callers.all(::inActivity) } }
            // A symbol is Activity-only when every use of it is
            .groupBy({ it.first }, { it.second })
            .map { (gap, activityOnly) -> gap.copy(activityOnly = activityOnly.all { it }) }
            .sortedBy { it.symbol }
    }

    /** The extension's own classes: their superclasses, interfaces and declared members. */
    private class Jar(val own: Set<String>) {
        val supers = HashMap<String, String>()
        val interfaces = HashMap<String, List<String>>()
        val declared = HashMap<String, MutableSet<String>>()
    }

    /**
     * Resolves [ref] as the JVM would for the extension: a member named on one of its own classes
     * that the class does not declare is inherited, so it is looked up in the first app class up
     * the hierarchy (and the app interfaces on the way), where it may be missing or a stub.
     */
    private fun resolveFrom(ref: Ref, jar: Jar): Gap? {
        val owner = ownerOf(ref.owner)
        if (owner !in jar.own) return resolve(ref)
        val key = when (ref) {
            is ClassRef -> return null
            is MethodRef -> ref.name + ref.desc
            is FieldRef -> ref.name + ":" + ref.desc
        }
        // The whole hierarchy of the extension's own classes and interfaces, and the app types it reaches
        val external = LinkedHashSet<String>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>().apply { add(owner) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            if (current !in jar.own) {
                external += current
                continue
            }
            if (key in jar.declared[current].orEmpty()) return null
            jar.supers[current]?.let(queue::add)
            jar.interfaces[current].orEmpty().forEach(queue::add)
        }
        val gaps = external.map { candidate ->
            when (ref) {
                is MethodRef -> resolve(ref.copy(owner = candidate))
                is FieldRef -> resolve(ref.copy(owner = candidate))
                is ClassRef -> null
            } ?: return null
        }
        return gaps.firstOrNull()
    }

    private fun isActivity(name: String, supers: Map<String, String>): Boolean {
        var current: String? = name
        repeat(16) {
            if (current == ACTIVITY) return true
            current = supers[current] ?: return false
        }
        return false
    }

    private sealed interface Ref {
        val owner: String
    }

    private data class ClassRef(override val owner: String) : Ref
    private data class MethodRef(override val owner: String, val name: String, val desc: String) : Ref
    private data class FieldRef(override val owner: String, val name: String, val desc: String) : Ref

    /** The class an owner names: arrays resolve to their element type, primitives to nothing. */
    private fun ownerOf(owner: String): String {
        if (!owner.startsWith("[")) return owner
        val element = Type.getType(owner).elementType
        return if (element.sort == Type.OBJECT) element.internalName else ""
    }

    private fun resolve(ref: Ref): Gap? {
        val owner = ownerOf(ref.owner)
        if (owner.isEmpty()) return null
        val cls = classOf(owner).getOrElse { return Gap(owner.dotted(), "class not found") }
        return when (ref) {
            is ClassRef -> null
            is MethodRef -> if (ref.owner.startsWith("[")) {
                null
            } else {
                memberCache.computeIfAbsent("${ref.owner}.${ref.name}${ref.desc}") { Optional.ofNullable(checkMethod(cls, ref)) }
                    .orElse(null)
            }
            is FieldRef ->
                memberCache.computeIfAbsent("${ref.owner}.${ref.name}:${ref.desc}") { Optional.ofNullable(checkField(cls, ref)) }
                    .orElse(null)
        }
    }

    private fun classOf(internalName: String): Result<Class<*>> = classCache.computeIfAbsent(internalName) {
        runCatching { Class.forName(internalName.dotted(), false, host) }
    }

    private fun checkMethod(cls: Class<*>, ref: MethodRef): Gap? {
        // invoke, invokeExact and the VarHandle accessors take any descriptor
        if (cls.name == "java.lang.invoke.MethodHandle" || cls.name == "java.lang.invoke.VarHandle") return null
        val symbol = "${cls.name}.${ref.name}${ref.desc}"
        return try {
            val declaring = if (ref.name == "<init>") {
                cls.takeIf { c -> c.declaredConstructors.any { Type.getConstructorDescriptor(it) == ref.desc } }
            } else {
                hierarchy(cls).firstOrNull { c -> c.declaredMethods.any { it.name == ref.name && Type.getMethodDescriptor(it) == ref.desc } }
            }
            when {
                declaring == null -> Gap(symbol, "no such method")
                "${ref.name}${ref.desc}" in stubsOf(declaring).methods -> Gap(symbol, "stub: throws \"Stub!\"")
                else -> null
            }
        } catch (e: LinkageError) {
            // The host class itself cannot be inspected: a type in one of its signatures is missing
            Gap(symbol, "host class ${cls.name} is broken: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun checkField(cls: Class<*>, ref: FieldRef): Gap? {
        val symbol = "${cls.name}.${ref.name}:${ref.desc}"
        return try {
            val declaring = hierarchy(cls).firstOrNull { c ->
                c.declaredFields.any { it.name == ref.name && Type.getDescriptor(it.type) == ref.desc }
            }
            when {
                declaring == null -> Gap(symbol, "no such field")
                ref.name in stubsOf(declaring).nullFields -> Gap(symbol, "stub: always null")
                else -> null
            }
        } catch (e: LinkageError) {
            Gap(symbol, "host class ${cls.name} is broken: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /**
     * What of a class is an empty stand-in. Suwayomi's android.jar keeps the Android API the
     * compatibility layer does not implement as stubs: methods that throw RuntimeException("Stub!"),
     * and static fields nothing assigns (android.util.Patterns.EMAIL_ADDRESS is null).
     */
    private class Stubs(val methods: Set<String>, val nullFields: Set<String>)

    private val stubCache = ConcurrentHashMap<String, Stubs>()

    private fun stubsOf(cls: Class<*>): Stubs = stubCache.computeIfAbsent(cls.name) {
        val bytes = (cls.classLoader ?: host).getResourceAsStream(cls.name.replace('.', '/') + ".class")?.use { it.readBytes() }
            ?: return@computeIfAbsent Stubs(emptySet(), emptySet())
        val methods = HashSet<String>()
        val staticFields = HashSet<String>()
        val assigned = HashSet<String>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): org.objectweb.asm.FieldVisitor? {
                    val reference = descriptor.startsWith("L") || descriptor.startsWith("[")
                    if (access and Opcodes.ACC_STATIC != 0 && reference && value == null) staticFields += name
                    return null
                }

                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitLdcInsn(value: Any?) {
                            if (value == "Stub!") methods += name + descriptor
                        }

                        override fun visitFieldInsn(opcode: Int, owner: String, field: String, desc: String) {
                            if (opcode == Opcodes.PUTSTATIC) assigned += field
                        }
                    }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        // Only a stub class's unassigned fields are stubs; elsewhere they may be set by other means.
        // android.jar's classes are stubs even when none of their methods are (android.util.Patterns)
        val stubClass = methods.isNotEmpty() || STUB_PACKAGES.any { cls.name.startsWith(it) }
        Stubs(methods, if (stubClass) staticFields - assigned else emptySet())
    }

    /** [cls], its superclasses and all their interfaces, the way the JVM resolves members. */
    private fun hierarchy(cls: Class<*>): Sequence<Class<*>> = sequence {
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>().apply { add(cls) }
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            yield(c)
            c.superclass?.let(queue::add)
            c.interfaces.forEach(queue::add)
        }
        // Interfaces inherit Object's public methods
        if (Modifier.isInterface(cls.modifiers) && Any::class.java !in seen) yield(Any::class.java)
    }

    private fun collect(className: String, bytes: ByteArray, refMap: MutableMap<Ref, MutableSet<String>>, jar: Jar) {
        val declared = jar.declared.getOrPut(className) { HashSet() }
        val refs = object {
            operator fun plusAssign(ref: Ref) {
                refMap.getOrPut(ref) { HashSet() } += className
            }
        }
        val methodVisitor = object : MethodVisitor(Opcodes.ASM9) {
            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                refs += MethodRef(owner, name, descriptor)
            }

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                refs += FieldRef(owner, name, descriptor)
            }

            override fun visitTypeInsn(opcode: Int, type: String) {
                refs += ClassRef(type)
            }

            override fun visitLdcInsn(value: Any?) {
                if (value is Type && value.sort == Type.OBJECT) refs += ClassRef(value.internalName)
                if (value is Type && value.sort == Type.ARRAY) refs += ClassRef(value.descriptor)
            }

            override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, type: String?) {
                type?.let { refs += ClassRef(it) }
            }

            override fun visitInvokeDynamicInsn(name: String?, descriptor: String?, bsm: Handle, vararg bsmArgs: Any?) {
                handle(bsm)
                bsmArgs.filterIsInstance<Handle>().forEach(::handle)
            }

            private fun handle(h: Handle) {
                when (h.tag) {
                    Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC ->
                        refs += FieldRef(h.owner, h.name, h.desc)
                    else -> refs += MethodRef(h.owner, h.name, h.desc)
                }
            }
        }
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                    superName?.let { jar.supers[className] = it }
                    jar.interfaces[className] = interfaces?.toList().orEmpty()
                    superName?.let { refs += ClassRef(it) }
                    interfaces?.forEach { refs += ClassRef(it) }
                }

                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    declared += name + descriptor
                    return methodVisitor
                }

                override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                    declared += "$name:$descriptor"
                    return null
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }

    private fun String.dotted() = replace('/', '.')

    private companion object {
        const val ACTIVITY = "android/app/Activity"
        val STUB_PACKAGES = listOf("android.", "androidx.", "dalvik.")
    }
}
