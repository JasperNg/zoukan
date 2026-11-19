package zoukan.extension

import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkFormatException
import com.android.apksig.util.DataSources
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.Locale
import java.util.jar.JarFile

/**
 * Signature checks. Each returns the SHA-256 of the signing certificates, the same fingerprint
 * Komikku compares with an extension store's signing key on Android.
 */
object ExtensionSignatures {

    /** A jar's signers, and its files as they were read while the signature was checked. */
    data class VerifiedJar(val signers: List<String>, val contents: JarContents)

    /**
     * Checks a jar's JAR (v1) signature: every entry except the manifest and the signature files
     * themselves must be signed, and all by the same certificate. That includes the rest of
     * META-INF (service files, multi-release classes), which a class loader can also act on.
     *
     * The files are read from the one open jar while their digests are checked, and returned, so
     * the caller can run exactly what was verified.
     *
     * A file that is not a jar, is cut off, or has an entry that does not match its signed digest
     * is refused with an [ExtensionException] too, so its callers report it as an extension error.
     * So is any other exception the JDK's zip and signature code throws on crafted bytes; an
     * [Error] is not caught: reading a file runs no extension code, so it is the app's own failure.
     */
    fun verifyJar(jar: File): VerifiedJar = try {
        verifyJarOrThrow(jar)
    } catch (e: IOException) {
        // ZipException: not a zip, or cut off
        throw ExtensionException(ExtensionException.Stage.SIGNATURE, "${jar.name} is damaged or not a jar: ${e.message}", e)
    } catch (e: SecurityException) {
        // An entry whose bytes do not match the digest its signature lists
        throw ExtensionException(ExtensionException.Stage.SIGNATURE, "${jar.name} does not match its signature: ${e.message}", e)
    } catch (e: RuntimeException) {
        // Crafted bytes the zip or signature parser does not expect (an index or a length out of range)
        throw ExtensionException(ExtensionException.Stage.SIGNATURE, "${jar.name} is damaged or not a jar: $e", e)
    }

    private fun verifyJarOrThrow(jar: File): VerifiedJar {
        val signers = mutableSetOf<String>()
        val entries = LinkedHashMap<String, ByteArray>()
        JarFile(jar, true).use { jf ->
            if (jf.size() > ExtensionLimits.MAX_ENTRIES) {
                throw ExtensionException(
                    ExtensionException.Stage.SIGNATURE,
                    "${jar.name} has too many entries (over ${ExtensionLimits.MAX_ENTRIES})",
                )
            }
            val budget = ZipBudget(ExtensionException.Stage.SIGNATURE, jar.name)
            jf.entries().asSequence()
                .filterNot { it.isDirectory || isSigningRelated(it.name) }
                .forEach { entry ->
                    // Reading the whole entry is what makes JarFile check its digest; the size is
                    // capped, since an entry can be a zip bomb
                    val bytes = jf.getInputStream(entry).use { budget.read(entry.name, it) }
                    val certs = entry.codeSigners?.map { it.signerCertPath.certificates.first() }
                        ?: throw ExtensionException(ExtensionException.Stage.SIGNATURE, "${entry.name} is not signed")
                    certs.forEach { signers += it.sha256() }
                    entries[entry.name] = bytes
                }
        }
        if (entries.isEmpty()) throw ExtensionException(ExtensionException.Stage.SIGNATURE, "${jar.name} is empty")
        if (signers.size != 1) {
            throw ExtensionException(ExtensionException.Stage.SIGNATURE, "Expected one signer, found ${signers.size}")
        }
        return VerifiedJar(signers.toList(), JarContents(entries))
    }

    /**
     * Whether a jar entry is part of the JAR signature rather than signed content: the manifest
     * and the signature files directly in META-INF (`*.SF`, `*.RSA`, `*.DSA`, `*.EC`, `SIG-*`),
     * as the JDK's own verifier defines them. Case-insensitive, like the JDK.
     */
    fun isSigningRelated(name: String): Boolean {
        val upper = name.uppercase(Locale.ROOT)
        if (!upper.startsWith("META-INF/")) return false
        val file = upper.removePrefix("META-INF/")
        if ('/' in file) return false
        return file == "MANIFEST.MF" ||
            SIGNATURE_SUFFIXES.any { file.endsWith(it) } ||
            file.startsWith("SIG-")
    }

    private val SIGNATURE_SUFFIXES = listOf(".SF", ".RSA", ".DSA", ".EC")

    /**
     * Checks an APK's v1, v2 and v3 signatures with apksig, as Android's package manager would, and
     * requires a v2 or newer one: only those cover every byte the other readers of the APK parse.
     */
    fun verifyApk(apk: ByteArray): List<String> {
        val result = try {
            ApkVerifier.Builder(DataSources.asDataSource(ByteBuffer.wrap(apk))).build().verify()
        } catch (e: ApkFormatException) {
            // Not an APK, or a damaged one: an extension error its callers report
            throw ExtensionException(ExtensionException.Stage.SIGNATURE, "The APK is damaged or not an APK: ${e.message}", e)
        } catch (e: IOException) {
            throw ExtensionException(ExtensionException.Stage.SIGNATURE, "The APK could not be read: ${e.message}", e)
        } catch (e: RuntimeException) {
            // apksig on crafted bytes it does not expect (BufferUnderflowException, an index or a length
            // out of range): the APK is damaged. An Error is not caught: no extension code runs here
            throw ExtensionException(ExtensionException.Stage.SIGNATURE, "The APK is damaged or not an APK: $e", e)
        }
        return signersOf(result)
    }

    /** The signing certificates' SHA-256 of a verified APK; fails when [result] does not verify. */
    internal fun signersOf(result: ApkVerifier.Result): List<String> {
        if (!result.isVerified) {
            val errors = result.errors +
                result.v1SchemeSigners.flatMap { it.errors } +
                result.v2SchemeSigners.flatMap { it.errors } +
                result.v3SchemeSigners.flatMap { it.errors }
            throw ExtensionException(
                ExtensionException.Stage.SIGNATURE,
                "APK signature does not verify: ${errors.joinToString("; ")}",
            )
        }
        // The v1 (JAR) scheme alone signs entries through the unsigned central directory, so the
        // manifest, asset and dex readers could see bytes it does not cover. v2 and newer sign every
        // byte outside the signing block; apksig refuses a v1+v2 APK with v2 stripped
        if (!result.isVerifiedUsingV2Scheme && !result.isVerifiedUsingV3Scheme && !result.isVerifiedUsingV31Scheme) {
            throw ExtensionException(
                ExtensionException.Stage.SIGNATURE,
                "APK is signed only with the v1 scheme; a v2 or newer signature is required",
            )
        }
        return result.signerCertificates.map { it.sha256() }
    }

    private fun Certificate.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
}
