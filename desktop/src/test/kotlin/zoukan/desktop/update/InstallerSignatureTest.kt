package zoukan.desktop.update

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import zoukan.desktop.Platform
import zoukan.desktop.update.InstallerSignature.ToolOutput
import zoukan.desktop.update.InstallerSignature.Verdict
import java.io.File

class InstallerSignatureTest {
    private val exe = File("C:/Users/me/AppData/Local/Zoukan/Zoukan.exe")
    private val msi = File("C:/Users/me/AppData/Roaming/Zoukan/updates/Zoukan-1.1.0-windows-x64.msi")

    /** What PowerShell prints for a signed file: the status, the certificate thumbprint, and the SHA-256 of its public key. */
    private fun signed(thumbprint: String, keyHash: String, crlf: Boolean = false): String {
        val sep = if (crlf) "\r\n" else "\n"
        return listOf("Valid", thumbprint, keyHash).joinToString(sep) + sep
    }

    private val ownThumbprint = "3ACE9B71C4F2D8E5A0B6C7D8E9F0A1B2C3D4E5F6"
    private val renewedThumbprint = "7B2F0A55D1E4C839A6B7D0C2E5F8A1B4C7D0E3F6"
    private val otherThumbprint = "0000111122223333444455556666777788889999"
    private val ownKeyHash = "3ACE9B71C4F2D8E5A0B6C7D8E9F0A1B2C3D4E5F6A1B2C3D4E5F6A7B8C9D0E1F2"
    private val otherKeyHash = "0000111122223333444455556666777788889999AAAABBBBCCCCDDDDEEEEFFFF"

    /** Windows, with what PowerShell prints for each file. */
    private fun windows(vararg signatures: Pair<File, String>, packaged: Boolean = true, signedRelease: Boolean = true) = InstallerSignature(
        platform = Platform.WINDOWS,
        packaged = packaged,
        signedRelease = signedRelease,
        ownExecutable = { exe },
        run = { _, environment ->
            val file = File(environment.getValue("ZOUKAN_VERIFY_FILE"))
            signatures.firstOrNull { it.first.absoluteFile == file.absoluteFile }?.let { ToolOutput(0, it.second) }
        },
    )

    @Test
    fun `an installer signed by the app's publisher is trusted`() {
        // The key hashes compare case-insensitively: PowerShell prints lowercase
        val verdict = windows(
            exe to signed(ownThumbprint, ownKeyHash.uppercase(), crlf = true),
            msi to signed(ownThumbprint, ownKeyHash),
        ).verify(msi)
        verdict shouldBe Verdict.Trusted(ownKeyHash)
    }

    @Test
    fun `a renewed certificate with the same key pair is still the publisher`() {
        // Same subject and key, new certificate (new thumbprint, new expiry): users keep updating
        val verdict = windows(
            exe to signed(ownThumbprint, ownKeyHash),
            msi to signed(renewedThumbprint, ownKeyHash),
        ).verify(msi)
        verdict shouldBe Verdict.Trusted(ownKeyHash)
    }

    @Test
    fun `another publisher, no signature or a broken one is rejected`() {
        val own = exe to signed(ownThumbprint, ownKeyHash)
        windows(own, msi to signed(otherThumbprint, otherKeyHash)).verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
        // A different key under the same subject is a different publisher
        windows(own, msi to signed(ownThumbprint, otherKeyHash)).verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
        windows(own, msi to "NotSigned\n").verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
        windows(own, msi to "HashMismatch\n$ownThumbprint\n$ownKeyHash\n").verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
        // Valid, but what came back is not a public key hash to compare
        windows(own, msi to "Valid\nCN=Zoukan Publisher, O=Zoukan\n").verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
        // The tool could not run on the installer
        windows(own).verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
    }

    @Test
    fun `an unsigned development build has no publisher to compare with`() {
        windows(exe to "NotSigned\n", msi to "NotSigned\n", packaged = false).verify(msi)
            .shouldBeInstanceOf<Verdict.Unchecked>()
    }

    @Test
    fun `a release built without a signing certificate has no publisher to compare with`() {
        // The build info says this release shipped unsigned: its own missing signature is normal,
        // not tampering, so updates rest on the checksum alone
        windows(exe to "NotSigned\n", msi to "NotSigned\n", signedRelease = false).verify(msi)
            .shouldBeInstanceOf<Verdict.Unchecked>()
        windows(exe to "NotSigned\n", msi to signed(ownThumbprint, otherKeyHash), signedRelease = false).verify(msi)
            .shouldBeInstanceOf<Verdict.Unchecked>()
        // The signature tool itself failing on the exe is not tampering either
        windows(msi to signed(ownThumbprint, ownKeyHash), signedRelease = false).verify(msi)
            .shouldBeInstanceOf<Verdict.Unchecked>()
        // But when the exe does carry a readable signature, it is compared whatever the build says
        windows(exe to signed(ownThumbprint, ownKeyHash), msi to signed(ownThumbprint, ownKeyHash), signedRelease = false)
            .verify(msi) shouldBe Verdict.Trusted(ownKeyHash)
        windows(exe to signed(ownThumbprint, ownKeyHash), msi to signed(otherThumbprint, otherKeyHash), signedRelease = false)
            .verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
    }

    @Test
    fun `an installed build whose own signature is missing or unreadable fails closed`() {
        val good = msi to signed(ownThumbprint, ownKeyHash)
        // An installed release is signed; no signature means it was stripped or never made
        windows(exe to "NotSigned\n", good).verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
        windows(exe to "HashMismatch\n$ownThumbprint\n$ownKeyHash\n", good).verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
        windows(exe to "Valid\n$ownThumbprint\nnot-a-key-hash\n", good).verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
        // The signature tool itself is missing or failed
        windows(good).verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
        // Not even the running executable's path could be found
        InstallerSignature(
            platform = Platform.WINDOWS,
            packaged = true,
            signedRelease = true,
            ownExecutable = { null },
            run = { _, _ -> ToolOutput(0, signed(ownThumbprint, ownKeyHash)) },
        ).verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
    }

    @Test
    fun `the verdict decision covers every combination of the two signature readings`() {
        InstallerSignature.decide(ownKeyHash, ownKeyHash) shouldBe Verdict.Trusted(ownKeyHash)
        InstallerSignature.decide(ownKeyHash, ownKeyHash.uppercase()) shouldBe Verdict.Trusted(ownKeyHash)
        InstallerSignature.decide(ownKeyHash, otherKeyHash).shouldBeInstanceOf<Verdict.Rejected>()
        InstallerSignature.decide(ownKeyHash, null).shouldBeInstanceOf<Verdict.Rejected>()
        InstallerSignature.decide(null, ownKeyHash).shouldBeInstanceOf<Verdict.Rejected>()
        InstallerSignature.decide(null, null).shouldBeInstanceOf<Verdict.Rejected>()
    }

    @Test
    fun `the file path never becomes part of the command`() {
        var seen: List<String> = emptyList()
        val odd = File("C:/x/a'; Remove-Item C:/ -Recurse; '.msi")
        InstallerSignature(
            platform = Platform.WINDOWS,
            packaged = true,
            ownExecutable = { exe },
            run = { command, _ ->
                seen = command
                ToolOutput(0, signed(ownThumbprint, ownKeyHash))
            },
        ).verify(odd)
        seen.any { "Remove-Item" in it } shouldBe false
    }

    @Test
    fun `macOS compares the Developer ID team`() {
        val dmg = File("/Users/me/Library/Application Support/Zoukan/updates/Zoukan-1.1.0-macos-arm64.dmg")
        fun mac(appTeam: String, dmgTeam: String, dmgVerifies: Boolean = true, appVerifies: Boolean = true, packaged: Boolean = true, toolRuns: Boolean = true, signedRelease: Boolean = true) = InstallerSignature(
            platform = Platform.MACOS,
            packaged = packaged,
            signedRelease = signedRelease,
            ownExecutable = { File("/Applications/Zoukan.app/Contents/MacOS/Zoukan") },
            run = { command, _ ->
                if (!toolRuns) {
                    null
                } else {
                    val target = command.last()
                    // absolutePath adds a drive letter when the test runs on Windows
                    val team = if (target.endsWith("Zoukan.app")) appTeam else dmgTeam
                    val fails = (target.endsWith(".dmg") && !dmgVerifies) || (target.endsWith("Zoukan.app") && !appVerifies)
                    when {
                        "--verify" in command -> ToolOutput(if (fails) 1 else 0, "")
                        else -> ToolOutput(0, "Executable=$target\nIdentifier=app.zoukan\nTeamIdentifier=$team\n")
                    }
                }
            },
        ).verify(dmg)
        mac("ABCDE12345", "ABCDE12345") shouldBe Verdict.Trusted("ABCDE12345")
        mac("ABCDE12345", "ZZZZZ99999").shouldBeInstanceOf<Verdict.Rejected>()
        mac("ABCDE12345", "not set").shouldBeInstanceOf<Verdict.Rejected>()
        mac("ABCDE12345", "ABCDE12345", dmgVerifies = false).shouldBeInstanceOf<Verdict.Rejected>()
        // Fail closed on the app's own signature too: unreadable, unverifiable, or no team
        mac("ABCDE12345", "ABCDE12345", toolRuns = false).shouldBeInstanceOf<Verdict.Rejected>()
        mac("ABCDE12345", "ABCDE12345", appVerifies = false).shouldBeInstanceOf<Verdict.Rejected>()
        mac("not set", "ABCDE12345").shouldBeInstanceOf<Verdict.Rejected>()
        // A development build (never packaged) has nothing to compare with
        mac("not set", "ABCDE12345", packaged = false).shouldBeInstanceOf<Verdict.Unchecked>()
        // So has a release built without a Developer ID (ad hoc signed, no team)
        mac("not set", "ABCDE12345", signedRelease = false).shouldBeInstanceOf<Verdict.Unchecked>()
        mac("ABCDE12345", "ABCDE12345", toolRuns = false, signedRelease = false).shouldBeInstanceOf<Verdict.Unchecked>()
    }
}
