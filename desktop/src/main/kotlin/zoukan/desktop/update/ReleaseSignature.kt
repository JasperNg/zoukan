package zoukan.desktop.update

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Checks the detached release signature of a downloaded installer against the key built into the app.
 *
 * GitHub's own SHA-256 comes from the same release as the installer, so anyone who can publish a
 * release controls both. The release job signs a short statement naming the installer and its
 * SHA-256 with a private key that is a separate secret; the signature is the release asset
 * `<installer name>.sig` (64 raw Ed25519 bytes). The app recomputes the SHA-256 of the file it
 * has and checks the signature with [publicKeyPem]. Anything else fails closed.
 *
 * A development build (not [packaged]) with no key is [InstallerSignature.Verdict.Unchecked]; a
 * packaged build with no key rejects every installer.
 */
class ReleaseSignature(
    private val publicKeyPem: String? = null,
    private val packaged: Boolean = System.getProperty("zoukan.packaged") == "true",
) {
    fun verify(installer: File, signature: File?): InstallerSignature.Verdict {
        if (publicKeyPem.isNullOrBlank()) {
            return if (packaged) {
                InstallerSignature.Verdict.Rejected("This build of Zoukan has no release key to check the update with. It was not opened.")
            } else {
                InstallerSignature.Verdict.Unchecked("this build has no release key")
            }
        }
        if (signature == null || !signature.isFile || signature.length() != SIGNATURE_BYTES) {
            return InstallerSignature.Verdict.Rejected("The update has no valid release signature. It was not opened.")
        }
        return try {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(decode(publicKeyPem)))
            val statement = "$STATEMENT_PREFIX\n${installer.name}\n${sha256Hex(installer)}\n"
            val valid = Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(statement.toByteArray(Charsets.UTF_8))
                verify(signature.readBytes())
            }
            if (valid) {
                InstallerSignature.Verdict.Trusted("release key")
            } else {
                InstallerSignature.Verdict.Rejected("The update's release signature does not match Zoukan's release key. It was not opened.")
            }
        } catch (e: GeneralSecurityException) {
            InstallerSignature.Verdict.Rejected("The update's release signature could not be checked. It was not opened.")
        } catch (e: IllegalArgumentException) {
            InstallerSignature.Verdict.Rejected("The update's release signature could not be checked. It was not opened.")
        } catch (e: IOException) {
            InstallerSignature.Verdict.Rejected("The update's release signature could not be checked. It was not opened.")
        }
    }

    private fun decode(pem: String): ByteArray = Base64.getMimeDecoder().decode(
        pem.lineSequence().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString(""),
    )

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SIGNATURE_BYTES = 64L
        private const val STATEMENT_PREFIX = "zoukan-release-v1"

        /** The release key built into the app, or null when the resource is missing. */
        fun builtIn(): ReleaseSignature = ReleaseSignature(
            ReleaseSignature::class.java.getResourceAsStream("/zoukan-release-key.pem")?.use { it.readBytes().decodeToString() },
        )
    }
}
