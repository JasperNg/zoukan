package zoukan.desktop.update

import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class ReleaseSignatureTest {
    @TempDir
    lateinit var dir: File

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun pem(pair: KeyPair): String =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) +
            "\n-----END PUBLIC KEY-----\n"

    private fun installer(bytes: ByteArray = ByteArray(200_000) { (it % 251).toByte() }) =
        File(dir, "Zoukan-1.2.0-windows-x64.msi").apply { writeBytes(bytes) }

    private fun sign(file: File, pair: KeyPair): File {
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val statement = "zoukan-release-v1\n" + file.name + "\n" + sha + "\n"
        val bytes = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(statement.toByteArray())
            sign()
        }
        return File(dir, file.name + ".sig").apply { writeBytes(bytes) }
    }

    @Test
    fun `an installer whose signature does not verify under the built-in key is rejected even when the checksum matches`() {
        val builtIn = keyPair()
        val attacker = keyPair()
        val file = installer()
        val sig = sign(file, attacker)

        ReleaseSignature(pem(builtIn), packaged = true).verify(file, sig)
            .shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()
    }

    @Test
    fun `a missing signature file is rejected`() {
        val key = keyPair()
        val file = installer()

        ReleaseSignature(pem(key), packaged = true).verify(file, null)
            .shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()
        ReleaseSignature(pem(key), packaged = true).verify(file, File(dir, "absent.sig"))
            .shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()
    }

    @Test
    fun `a changed installer is rejected`() {
        val key = keyPair()
        val file = installer()
        val sig = sign(file, key)
        file.writeBytes(file.readBytes().also { it[10] = (it[10] + 1).toByte() })

        ReleaseSignature(pem(key), packaged = true).verify(file, sig)
            .shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()
    }

    @Test
    fun `a signature made for another installer name is rejected`() {
        val key = keyPair()
        val file = installer()
        val sig = sign(file, key)
        val renamed = File(dir, "Other.msi").apply { writeBytes(file.readBytes()) }

        ReleaseSignature(pem(key), packaged = true).verify(renamed, sig)
            .shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()
    }

    @Test
    fun `an installer signed with the built-in key is trusted`() {
        val key = keyPair()
        val file = installer()

        ReleaseSignature(pem(key), packaged = true).verify(file, sign(file, key))
            .shouldBeInstanceOf<InstallerSignature.Verdict.Trusted>()
    }

    @Test
    fun `a packaged build without a release key rejects every installer`() {
        val key = keyPair()
        val file = installer()

        ReleaseSignature(null, packaged = true).verify(file, sign(file, key))
            .shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()
    }

    @Test
    fun `a development build without a release key leaves the installer unchecked`() {
        val file = installer()

        ReleaseSignature(null, packaged = false).verify(file, null)
            .shouldBeInstanceOf<InstallerSignature.Verdict.Unchecked>()
    }
}
