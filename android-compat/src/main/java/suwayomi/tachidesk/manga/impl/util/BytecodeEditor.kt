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
import org.objectweb.asm.Handle
// ZOUKAN -->
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.TypePath
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
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
     *
     * A subclass of OkHttpClient calls the constructor without a NEW (`ALOAD 0; INVOKESPECIAL
     * OkHttpClient.<init>()V`). That call becomes `INVOKESTATIC ExtensionHttp.newBuilder();
     * INVOKESPECIAL OkHttpClient.<init>(Builder)V`, so the subclass has the app's DNS as well. This
     * puts one more value on the stack, so the method's max stack grows by one. Subclasses of
     * OkHttpClient.Builder are left alone: setting the DNS there is not a constructor swap.
     */
    private class ExtensionHttpRewriter(
        mv: MethodVisitor?,
    ) : MethodVisitor(Opcodes.ASM9, mv) {
        private var pendingNew: String? = null
        private var pendingDup = false
        private var rewroteSuperCall = false

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
            if (opcode == Opcodes.INVOKESPECIAL && owner == "okhttp3/OkHttpClient" && name == "<init>" && descriptor == "()V") {
                // No NEW before it: the super (or this) call of a subclass constructor, on the
                // uninitialized `this`. The client is built from the builder the app's DNS is set on
                super.visitMethodInsn(Opcodes.INVOKESTATIC, EXTENSION_HTTP, "newBuilder", "()Lokhttp3/OkHttpClient\$Builder;", false)
                super.visitMethodInsn(opcode, owner, name, "(Lokhttp3/OkHttpClient\$Builder;)V", isInterface)
                rewroteSuperCall = true
                return
            }
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
            // A rewritten super call has the builder on the stack besides `this`: one more value
            // than before, and the class writer does not recompute the max stack
            super.visitMaxs(if (rewroteSuperCall) maxStack + 1 else maxStack, maxLocals)
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
        // ZOUKAN -->
        // Every reference to a replaced class is renamed, wherever it is: instructions and
        // descriptors, but also the stack map frames, local variable tables, generic signatures,
        // try-catch types, class constants, method handles, annotations and the super class.
        // Suwayomi's visitor renamed only instructions and descriptors, which works for dex2jar's
        // output but not for a store's jar: its stack map frames still named
        // java/text/SimpleDateFormat for a local holding the replacement, and the verifier rejected
        // the method ("Inconsistent stackmap frames"). The frames are renamed, not recomputed, so
        // the class needs no class loader to resolve common super classes. A name already replaced
        // is not in the map, so a class rewritten before keeps its references (idempotent).
        val http =
            object : ClassVisitor(Opcodes.ASM9, cw) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? =
                    super.visitMethod(access, name, descriptor, signature, exceptions)?.let(::ExtensionHttpRewriter)
            }
        cr.accept(ClassRemapper(http, replacedClassRemapper), 0)
        // ZOUKAN <--
        return pair.first to cw.toByteArray()
    }

    // ZOUKAN -->
    /** Renames each of [classesToReplace] to its copy under [REPLACEMENT_PATH]. */
    private val replacedClassRemapper by lazy {
        SimpleRemapper(classesToReplace.associateWith { "$REPLACEMENT_PATH/$it" })
    }
    // ZOUKAN <--

    private fun write(pair: Pair<Path, ByteArray>) {
        Files.write(
            pair.first,
            pair.second,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
}
