/*
 * The dex2jar settings and the asset copy are adapted from Suwayomi-Server (PackageTools.kt and
 * Extension.kt at d5215280). Copyright (C) Contributors to the Suwayomi project.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package zoukan.extension

import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.reader.MultiDexFileReader
import com.googlecode.dex2jar.tools.BaksmaliBaseDexExceptionHandler
import suwayomi.tachidesk.manga.impl.util.BytecodeEditor
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * The fallback for stores that publish only APKs: converts an APK's dex code to a JVM jar with
 * dex2jar, applies Suwayomi's bytecode rewrites (for example java.text.SimpleDateFormat to an
 * ICU-backed copy that behaves like Android's), and copies the APK's assets and its decoded
 * manifest into the jar.
 *
 * R8-minified APKs, which is every current Keiyoushi APK, fail JVM bytecode verification after
 * conversion: R8 inlines trivial subclass constructors (new-instance of a subclass followed by the
 * superclass constructor), which ART accepts and the JVM refuses, and dex2jar keeps the dex
 * register types. So stores with jars (Keiyoushi) are installed from their jars.
 */
object ApkConverter {

    fun convert(apk: File, jar: File) {
        val tmp = File(jar.path + ".tmp").apply { delete() }
        try {
            if (apk.length() > ExtensionLimits.MAX_DOWNLOAD_BYTES) {
                throw ExtensionException(
                    ExtensionException.Stage.CONVERT,
                    "${apk.name} is too large (over ${ExtensionLimits.megabytes(ExtensionLimits.MAX_DOWNLOAD_BYTES)} MB)",
                )
            }
            val handler = BaksmaliBaseDexExceptionHandler()
            // MultiDexFileReader accepts the whole APK and reads every classes*.dex in it
            Dex2jar.from(MultiDexFileReader.open(apk.readBytes()))
                .withExceptionHandler(handler)
                .reUseReg(false)
                .topoLogicalSort()
                .skipDebug(true)
                .optimizeSynchronized(false)
                .printIR(false)
                .noCode(false)
                .skipExceptions(false)
                .dontSanitizeNames(true)
                .computeFrames(true)
                .to(tmp.toPath())
            if (handler.hasException()) {
                val errorFile = File(jar.path + "-dex2jar-error.txt")
                handler.dump(errorFile.toPath(), emptyArray())
                throw ExtensionException(
                    ExtensionException.Stage.CONVERT,
                    "dex2jar reported errors, see ${errorFile.name}",
                )
            }
            BytecodeEditor.fixAndroidClasses(tmp.toPath())
            copyAssetsAndManifest(apk, tmp)
            Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: ExtensionException) {
            throw e
        } catch (e: Exception) {
            throw ExtensionException(ExtensionException.Stage.CONVERT, "dex2jar failed: ${e.message}", e)
        } finally {
            tmp.delete()
        }
    }

    private fun copyAssetsAndManifest(apk: File, jar: File) {
        val manifest = ExtensionManifest.manifestXmlOfApk(apk)
        FileSystems.newFileSystem(URI.create("jar:" + jar.toURI()), emptyMap<String, Any>()).use { fs ->
            Files.writeString(fs.getPath("/AndroidManifest.xml"), manifest)
            ZipFile(apk).use { zip ->
                var count = 0
                var copied = 0L
                zip.entries().asSequence()
                    .filter { it.name.startsWith("assets/") && !it.isDirectory }
                    .forEach { entry ->
                        if (++count > ExtensionLimits.MAX_ENTRIES) {
                            throw ExtensionException(
                                ExtensionException.Stage.CONVERT,
                                "${apk.name} has too many assets (over ${ExtensionLimits.MAX_ENTRIES})",
                            )
                        }
                        val target = fs.getPath("/" + entry.name)
                        target.parent?.let { Files.createDirectories(it) }
                        // The zipfs of the jar is in memory until it closes, so the copy is capped
                        val limit = minOf(ExtensionLimits.MAX_ENTRY_BYTES, ExtensionLimits.MAX_TOTAL_BYTES - copied)
                        zip.getInputStream(entry).use { input ->
                            Files.newOutputStream(target).use { out ->
                                copied += input.copyAtMost(out, limit, ExtensionException.Stage.CONVERT, entry.name)
                            }
                        }
                    }
            }
        }
    }

    /** The hex SHA-256 of [file], like the certificate fingerprints in ExtensionSignatures. */
    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
