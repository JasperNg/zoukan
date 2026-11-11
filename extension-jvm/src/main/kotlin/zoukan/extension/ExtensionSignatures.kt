package zoukan.extension

import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.jar.JarFile

/**
 * Signature checks. Each returns the SHA-256 of the signing certificates, the same fingerprint
 * Komikku compares with an extension store's signing key on Android.
 */
object ExtensionSignatures {

    /**
     * Checks a jar's JAR (v1) signature: every entry outside META-INF must be signed, and all by the
     * same certificate.
     */
    fun verifyJar(jar: File): List<String> {
        val signers = mutableSetOf<String>()
        var entries = 0
        JarFile(jar, true).use { jf ->
            jf.entries().asSequence()
                .filterNot { it.isDirectory || it.name.startsWith("META-INF/") }
                .forEach { entry ->
                    // Reading the whole entry is what makes JarFile check its digest
                    jf.getInputStream(entry).use { it.readAllBytes() }
                    val certs = entry.codeSigners?.map { it.signerCertPath.certificates.first() }
                        ?: throw ExtensionException(ExtensionException.Stage.SIGNATURE, "${entry.name} is not signed")
                    certs.forEach { signers += it.sha256() }
                    entries++
                }
        }
        if (entries == 0) throw ExtensionException(ExtensionException.Stage.SIGNATURE, "${jar.name} is empty")
        if (signers.size != 1) {
            throw ExtensionException(ExtensionException.Stage.SIGNATURE, "Expected one signer, found ${signers.size}")
        }
        return signers.toList()
    }

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
