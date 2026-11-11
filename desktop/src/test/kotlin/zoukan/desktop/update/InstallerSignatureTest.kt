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

    /** Windows, with what PowerShell prints for each file. */
    private fun windows(vararg signatures: Pair<File, String>, packaged: Boolean = true) = InstallerSignature(
        platform = Platform.WINDOWS,
        packaged = packaged,
        ownExecutable = { exe },
        run = { _, environment ->
            val file = File(environment.getValue("ZOUKAN_VERIFY_FILE"))
            signatures.firstOrNull { it.first.absoluteFile == file.absoluteFile }?.let { ToolOutput(0, it.second) }
        },
    )

    @Test
    fun `an installer signed by the app's publisher is trusted`() {
        val verdict = windows(exe to "Valid\r\nCN=Zoukan Publisher, O=Zoukan\r\n", msi to "Valid\r\nCN=Zoukan Publisher, O=Zoukan\r\n").verify(msi)
        verdict shouldBe Verdict.Trusted("CN=Zoukan Publisher, O=Zoukan")
    }

    @Test
    fun `another publisher, no signature or a broken one is rejected`() {
        val own = exe to "Valid\nCN=Zoukan Publisher, O=Zoukan\n"
        windows(own, msi to "Valid\nCN=Someone Else\n").verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
        windows(own, msi to "NotSigned\n").verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
        windows(own, msi to "HashMismatch\nCN=Zoukan Publisher, O=Zoukan\n").verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
        // The tool could not run on the installer
        windows(own).verify(msi).shouldBeInstanceOf<Verdict.Rejected>()
    }

    @Test
    fun `an unsigned or development build has no publisher to compare with`() {
        windows(exe to "NotSigned\n", msi to "NotSigned\n").verify(msi).shouldBeInstanceOf<Verdict.Unchecked>()
        windows(exe to "Valid\nCN=JDK vendor\n", msi to "NotSigned\n", packaged = false).verify(msi)
            .shouldBeInstanceOf<Verdict.Unchecked>()
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
                ToolOutput(0, "Valid\nCN=Zoukan\n")
            },
        ).verify(odd)
        seen.any { "Remove-Item" in it } shouldBe false
    }

    @Test
    fun `macOS compares the Developer ID team`() {
        val dmg = File("/Users/me/Library/Application Support/Zoukan/updates/Zoukan-1.1.0-macos-arm64.dmg")
        fun mac(appTeam: String, dmgTeam: String, dmgVerifies: Boolean = true) = InstallerSignature(
            platform = Platform.MACOS,
            packaged = true,
            ownExecutable = { File("/Applications/Zoukan.app/Contents/MacOS/Zoukan") },
            run = { command, _ ->
                val target = command.last()
                // absolutePath adds a drive letter when the test runs on Windows
                val team = if (target.endsWith("Zoukan.app")) appTeam else dmgTeam
                when {
                    "--verify" in command -> ToolOutput(if (target.endsWith(".dmg") && !dmgVerifies) 1 else 0, "")
                    else -> ToolOutput(0, "Executable=$target\nIdentifier=app.zoukan\nTeamIdentifier=$team\n")
                }
            },
        ).verify(dmg)
        mac("ABCDE12345", "ABCDE12345") shouldBe Verdict.Trusted("ABCDE12345")
        mac("ABCDE12345", "ZZZZZ99999").shouldBeInstanceOf<Verdict.Rejected>()
        mac("ABCDE12345", "not set").shouldBeInstanceOf<Verdict.Rejected>()
        mac("ABCDE12345", "ABCDE12345", dmgVerifies = false).shouldBeInstanceOf<Verdict.Rejected>()
        // An ad hoc signed app (no Developer ID) cannot check its updates
        mac("not set", "ABCDE12345").shouldBeInstanceOf<Verdict.Unchecked>()
    }
}
