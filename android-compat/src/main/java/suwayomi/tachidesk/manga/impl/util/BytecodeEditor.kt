package suwayomi.tachidesk.manga.impl.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
// ZOUKAN -->
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.TypePath
// ZOUKAN <--
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.streams.asSequence

object BytecodeEditor {
    private val logger = KotlinLogging.logger {}

    /**
     * Replace some java class references inside a jar with new ones that behave like Androids
     *
     * @param jarFile The JarFile to replace class references in
     */
    fun fixAndroidClasses(jarFile: Path) {
        FileSystems.newFileSystem(jarFile, null as ClassLoader?)?.use {
            Files
                .walk(it.getPath("/"))
                .asSequence()
                .filterNotNull()
                .filterNot(Files::isDirectory)
                .mapNotNull(::getClassBytes)
                .map(::transform)
                .forEach(::write)
        }
    }

    // ZOUKAN -->
    /**
     * The same rewrite as [fixAndroidClasses], for one class's bytes: what a class loader applies
     * when it defines a class from a jar that was not converted (a store's signed jar, whose bytes
     * must stay as signed). Returns [bytes] itself when the class names none of the replaced classes
     * (nor OkHttpClient, whose constructors become ExtensionHttp calls: see [ExtensionHttpRewriter]).
     * Idempotent: a class rewritten before (an APK converted at install) keeps its references as
     * they are (ASM keeps the old constant pool entries, so it may be rewritten again, to the same).
     */
    @JvmStatic
    fun fixAndroidClass(bytes: ByteArray): ByteArray {
        if (!referencesReplacedClass(bytes)) return bytes
        return transform(null to bytes).second
    }

    /** Whether the class may name a replaced class not yet replaced (a cheap byte scan, may say yes needlessly). */
    private fun referencesReplacedClass(bytes: ByteArray): Boolean {
        // OkHttpClient and OkHttpClient$Builder, whose constructors ExtensionHttpRewriter replaces
        if (indexOf(bytes, okHttpClientBytes, 0) >= 0) return true
        val prefix = "$REPLACEMENT_PATH/".toByteArray()
        for (target in replacedClassBytes) {
            var at = indexOf(bytes, target, 0)
            while (at >= 0) {
                val alreadyReplaced = at >= prefix.size && prefix.indices.all { bytes[at - prefix.size + it] == prefix[it] }
                if (!alreadyReplaced) return true
                at = indexOf(bytes, target, at + 1)
            }
        }
        return false
    }

    private val replacedClassBytes by lazy { classesToReplace.map { it.toByteArray() } }

    private val okHttpClientBytes = "okhttp3/OkHttpClient".toByteArray()

    private const val EXTENSION_HTTP = "xyz/nulldev/androidcompat/network/ExtensionHttp"

    /** The no-argument constructors replaced, and the [EXTENSION_HTTP] method that replaces each. */
    private val httpConstructors =
        mapOf(
            "okhttp3/OkHttpClient" to "newClient",
            "okhttp3/OkHttpClient\$Builder" to "newBuilder",
        )

    /**
     * Rewrites an extension's `new OkHttpClient()` and `new OkHttpClient.Builder()` into calls of
     * ExtensionHttp, whose client is the same fresh one but resolves names through the app's DNS
     * (over HTTPS when it is on) instead of the system's.
     *
     * A constructor call is the sequence `NEW type; DUP; INVOKESPECIAL type.<init>()V` (what javac,
     * kotlinc and dex2jar emit for a no-argument constructor), replaced by
     * `INVOKESTATIC ExtensionHttp.factory()Ltype;`, which leaves the same initialized reference on
     * the stack. The NEW and DUP are held back until the next instruction shows whether they start
     * such a sequence; anything else in between (an argument, a label, a frame) writes them out
     * unchanged. A NEW nested in another constructor's arguments (`Foo(OkHttpClient())`) is matched
     * the same way, as only the innermost NEW is held. The stack never grows (one slot instead of
     * two), and no frame lies inside a replaced sequence, so the existing max stack and stack map
     * frames stay valid.
     */
    private class ExtensionHttpRewriter(
        mv: MethodVisitor?,
    ) : MethodVisitor(Opcodes.ASM9, mv) {
        private var pendingNew: String? = null
        private var pendingDup = false

        private fun flush() {
            val type = pendingNew ?: return
            pendingNew = null
            super.visitTypeInsn(Opcodes.NEW, type)
            if (pendingDup) super.visitInsn(Opcodes.DUP)
            pendingDup = false
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String?,
        ) {
            flush()
            if (opcode == Opcodes.NEW && type in httpConstructors) {
                pendingNew = type
                return
            }
            super.visitTypeInsn(opcode, type)
        }

        override fun visitInsn(opcode: Int) {
            if (opcode == Opcodes.DUP && pendingNew != null && !pendingDup) {
                pendingDup = true
                return
            }
            flush()
            super.visitInsn(opcode)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean,
        ) {
            val type = pendingNew
            if (type != null && pendingDup && opcode == Opcodes.INVOKESPECIAL && owner == type && name == "<init>" && descriptor == "()V") {
                pendingNew = null
                pendingDup = false
                super.visitMethodInsn(Opcodes.INVOKESTATIC, EXTENSION_HTTP, httpConstructors.getValue(type), "()L$type;", false)
                return
            }
            flush()
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            flush()
            super.visitIntInsn(opcode, operand)
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            flush()
            super.visitVarInsn(opcode, varIndex)
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
        ) {
            flush()
            super.visitFieldInsn(opcode, owner, name, descriptor)
        }

        override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?,
        ) {
            flush()
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: Label?,
        ) {
            flush()
            super.visitJumpInsn(opcode, label)
        }

        override fun visitLabel(label: Label?) {
            flush()
            super.visitLabel(label)
        }

        override fun visitLdcInsn(value: Any?) {
            flush()
            super.visitLdcInsn(value)
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) {
            flush()
            super.visitIincInsn(varIndex, increment)
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label?,
            vararg labels: Label?,
        ) {
            flush()
            super.visitTableSwitchInsn(min, max, dflt, *labels)
        }

        override fun visitLookupSwitchInsn(
            dflt: Label?,
            keys: IntArray?,
            labels: Array<out Label>?,
        ) {
            flush()
            super.visitLookupSwitchInsn(dflt, keys, labels)
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String?,
            numDimensions: Int,
        ) {
            flush()
            super.visitMultiANewArrayInsn(descriptor, numDimensions)
        }

        override fun visitInsnAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String?,
            visible: Boolean,
        ): AnnotationVisitor? {
            flush()
            return super.visitInsnAnnotation(typeRef, typePath, descriptor, visible)
        }

        override fun visitFrame(
            type: Int,
            numLocal: Int,
            local: Array<out Any>?,
            numStack: Int,
            stack: Array<out Any>?,
        ) {
            flush()
            super.visitFrame(type, numLocal, local, numStack, stack)
        }

        override fun visitLineNumber(
            line: Int,
            start: Label?,
        ) {
            flush()
            super.visitLineNumber(line, start)
        }

        override fun visitTryCatchBlock(
            start: Label?,
            end: Label?,
            handler: Label?,
            type: String?,
        ) {
            flush()
            super.visitTryCatchBlock(start, end, handler, type)
        }

        override fun visitMaxs(
            maxStack: Int,
            maxLocals: Int,
        ) {
            flush()
            super.visitMaxs(maxStack, maxLocals)
        }

        override fun visitEnd() {
            flush()
            super.visitEnd()
        }
    }

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
        from: Int,
    ): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
    // ZOUKAN <--

    /**
     * Get class bytes from a [Path]
     *
     * @param path The path entry to get the class bytes from
     *
     * @return [Pair] of the [Path] plus the class [ByteArray], or null if it's not a valid class
     */
    private fun getClassBytes(path: Path): Pair<Path, ByteArray>? {
        return try {
            if (path.toString().endsWith(".class")) {
                val bytes = Files.readAllBytes(path)
                if (bytes.size < 4) {
                    // Invalid class size
                    return null
                }
                val cafebabe =
                    String.format(
                        "%02X%02X%02X%02X",
                        bytes[0],
                        bytes[1],
                        bytes[2],
                        bytes[3],
                    )
                if (cafebabe.lowercase() != "cafebabe") {
                    // Corrupted class
                    return null
                }

                path to bytes
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Error loading class from Path: $path" }
            null
        }
    }

    /**
     * The path where replacement classes will reside
     */
    private const val REPLACEMENT_PATH = "xyz/nulldev/androidcompat/replace"

    /**
     * List of classes that will be replaced
     */
    private val classesToReplace =
        listOf(
            "java/text/SimpleDateFormat",
        )

    /**
     * Replace direct references to the class, used on places
     * that don't have any other text then the class
     *
     * @return [String] of class or null if [String] was null
     */
    private fun String?.replaceDirectly() =
        when (this) {
            null -> null
            in classesToReplace -> "$REPLACEMENT_PATH/$this"
            else -> this
        }

    /**
     * Replace references to the class, used in places that have
     * other text around the class references
     *
     * @return [String] with class references replaced, or null if [String] was null
     */
    private fun String?.replaceIndirectly(): String? {
        if (this == null) return null
        var classReference: String = this
        classesToReplace.forEach {
            // ZOUKAN -->
            // Only as a descriptor's class name (Ljava/text/SimpleDateFormat; or <...>), so a
            // name already replaced is not prefixed twice
            classReference = classReference.replace(Regex("(?<=L)${Regex.escape(it)}(?=[;<])"), "$REPLACEMENT_PATH/$it")
            // ZOUKAN <--
        }
        return classReference
    }

    /**
     * Replace all references to certain classes inside the class file
     * with ones that behave more like Androids
     *
     * @param pair Class bytecode to load into ASM for ease of modification
     *
     * @return [ByteArray] with modified bytecode
     */
    private fun <P> transform(pair: Pair<P, ByteArray>): Pair<P, ByteArray> {
        // Read the class and prepare to modify it
        val cr = ClassReader(pair.second)
        val cw = ClassWriter(cr, 0)
        // Modify the class
        cr.accept(
            object : ClassVisitor(/* ZOUKAN --> */Opcodes.ASM9/* ZOUKAN <-- */, cw) {
                // Modify field descriptor, for example
                // class MangaYes {
                //     val format = SimpleDateFormat("YYYY-MM-dd")
                // }
                override fun visitField(
                    access: Int,
                    name: String?,
                    desc: String?,
                    signature: String?,
                    cst: Any?,
                ): FieldVisitor? {
                    logger.trace { "CLass Field" to "${desc.replaceIndirectly()}: ${cst?.let { it::class.java.simpleName }}: $cst" }
                    return super.visitField(access, name, desc.replaceIndirectly(), signature, cst)
                }

                override fun visit(
                    version: Int,
                    access: Int,
                    name: String?,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    logger.trace { "Visiting $name: $signature: $superName" }
                    super.visit(version, access, name, signature, superName, interfaces)
                }

                // Modify method bytecode, for example
                // class MangaYes {
                //     fun fetchChapterList() {
                //         SimpleDateFormat("YYYY-MM-dd")
                //     }
                // }
                override fun visitMethod(
                    access: Int,
                    name: String,
                    desc: String,
                    signature: String?,
                    exceptions: Array<String?>?,
                ): MethodVisitor {
                    logger.trace { "Processing method $name: ${desc.replaceIndirectly()}: $signature" }
                    val mv: MethodVisitor? =
                        super.visitMethod(
                            access,
                            name,
                            desc.replaceIndirectly(),
                            signature,
                            exceptions,
                        )
                    return /* ZOUKAN --> */ExtensionHttpRewriter(/* ZOUKAN <-- */object : MethodVisitor(/* ZOUKAN --> */Opcodes.ASM9/* ZOUKAN <-- */, mv) {
                        override fun visitLdcInsn(cst: Any?) {
                            logger.trace { "Ldc" to "${cst?.let { "${it::class.java.simpleName}: $it" }}" }
                            super.visitLdcInsn(cst)
                        }

                        // Replace method type, for example
                        // val format = DateFormat()
                        // fun fetchChapterList() {
                        //     if (format is SimpleDateFormat)
                        // }
                        override fun visitTypeInsn(
                            opcode: Int,
                            type: String?,
                        ) {
                            logger.trace {
                                "Type" to "$opcode: ${type.replaceDirectly()}"
                            }
                            super.visitTypeInsn(
                                opcode,
                                type.replaceDirectly(),
                            )
                        }

                        // Replace method field, for example
                        // fun fetchChapterList() {
                        //     val format = SimpleDateFormat("YYYY-MM-dd")
                        // }
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            desc: String?,
                            itf: Boolean,
                        ) {
                            logger.trace {
                                "Method" to "$opcode: ${owner.replaceDirectly()}: $name: ${desc.replaceIndirectly()}"
                            }
                            super.visitMethodInsn(
                                opcode,
                                owner.replaceDirectly(),
                                name,
                                desc.replaceIndirectly(),
                                itf,
                            )
                        }

                        // Replace class field call from method, for example
                        // val format = SimpleDateFormat("YYYY-MM-dd")
                        // fun fetchChapterList() {
                        //     format.format(Date())
                        // }
                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            desc: String?,
                        ) {
                            logger.trace { "Field" to "$opcode: $owner: $name: ${desc.replaceIndirectly()}" }
                            super.visitFieldInsn(opcode, owner, name, desc.replaceIndirectly())
                        }

                        override fun visitInvokeDynamicInsn(
                            name: String?,
                            desc: String?,
                            bsm: Handle?,
                            vararg bsmArgs: Any?,
                        ) {
                            logger.trace { "InvokeDynamic" to "$name: $desc" }
                            super.visitInvokeDynamicInsn(name, desc, bsm, *bsmArgs)
                        }
                    }/* ZOUKAN --> */)/* ZOUKAN <-- */
                }
            },
            0,
        )
        return pair.first to cw.toByteArray()
    }

    private fun write(pair: Pair<Path, ByteArray>) {
        Files.write(
            pair.first,
            pair.second,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
}
