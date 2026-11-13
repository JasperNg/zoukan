package zoukan.extension

import com.android.apksig.ApkVerifier
import java.io.File
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
     */
    fun verifyJar(jar: File): VerifiedJar {
        val signers = mutableSetOf<String>()
        val entries = LinkedHashMap<String, ByteArray>()
        JarFile(jar, true).use { jf ->
            jf.entries().asSequence()
                .filterNot { it.isDirectory || isSigningRelated(it.name) }
                .forEach { entry ->
                    // Reading the whole entry is what makes JarFile check its digest
                    val bytes = jf.getInputStream(entry).use { it.readAllBytes() }
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

    /** Checks an APK's v1, v2 and v3 signatures with apksig, as Android's package manager would. */
    fun verifyApk(apk: File): List<String> {
        val result = ApkVerifier.Builder(apk).build().verify()
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
        return result.signerCertificates.map { it.sha256() }
    }

    private fun Certificate.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
}
