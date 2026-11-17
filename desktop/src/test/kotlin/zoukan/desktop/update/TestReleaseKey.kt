package zoukan.desktop.update

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/**
 * A release signing key made for one test, never the release key: signs installers the way the
 * release job does (ReleaseSignature's statement) and gives the [ReleaseSignature] that trusts it.
 */
class TestReleaseKey {
    private val pair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private val pem: String =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) +
            "\n-----END PUBLIC KEY-----\n"

    /** Checks with this key as a packaged build would: no key, or a wrong one, is a rejection. */
    val verifier: ReleaseSignature get() = ReleaseSignature(pem, packaged = true)

    /** The 64-byte `<name>.sig` for an installer called [name] with [bytes]. */
    fun sign(name: String, bytes: ByteArray): ByteArray {
        val statement = "zoukan-release-v1\n$name\n${sha256(bytes)}\n"
        return Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(statement.toByteArray())
            sign()
        }
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
