package zoukan.desktop.update

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import zoukan.desktop.Platform
import zoukan.desktop.outputWithin
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks who signed a downloaded installer before it is opened.
 *
 * The SHA-256 GitHub lists only shows the download is the file GitHub has; someone who can publish
 * a release (a stolen token) controls both. The code signature is made with a key that is not on
 * GitHub, so an update must be signed by the same publisher as the app that is running: the same
 * Authenticode certificate subject on Windows, the same Developer ID team on macOS. That is the
 * rule Android applies to app updates.
 *
 * A build that is not signed itself (a development build, or a release made without a
 * certificate) has no publisher to compare with; its updates are [Verdict.Unchecked] and rest on
 * the checksum alone, as before.
 */
class InstallerSignature(
    private val platform: Platform = Platform.current,
    private val packaged: Boolean = System.getProperty("zoukan.packaged") == "true",
    /** The running app: Zoukan.exe on Windows, a path inside Zoukan.app on macOS. */
    private val ownExecutable: () -> File? = {
        ProcessHandle.current().info().command().map(::File).orElse(null)
    },
    private val run: (command: List<String>, environment: Map<String, String>) -> ToolOutput? = { command, environment -> runTool(command, environment) },
) {
    data class ToolOutput(val exitCode: Int, val text: String)

    sealed interface Verdict {
        /** Signed by [publisher], the publisher of this app. */
        data class Trusted(val publisher: String) : Verdict

        /** This app is not signed, so there is nothing to compare with. */
        data class Unchecked(val reason: String) : Verdict

        data class Rejected(val reason: String) : Verdict
    }

    fun verify(installer: File): Verdict {
        if (!packaged) return Verdict.Unchecked("this is not an installed build")
        val own = ownExecutable()?.let { executable ->
            when (platform) {
                Platform.WINDOWS -> windowsPublisher(executable)
                Platform.MACOS -> appBundle(executable)?.let(::macTeam)
                Platform.LINUX -> null
            }
        } ?: return Verdict.Unchecked("this build of Zoukan is not signed")
        val theirs = when (platform) {
            Platform.WINDOWS -> windowsPublisher(installer)
            Platform.MACOS -> macTeam(installer)
            Platform.LINUX -> null
        } ?: return Verdict.Rejected("The installer is not signed, or its signature is not valid. It was not opened.")
        if (theirs != own) {
            return Verdict.Rejected("The installer is signed by \"$theirs\", not by Zoukan's publisher \"$own\". It was not opened.")
        }
        return Verdict.Trusted(own)
    }

    /** The subject of the certificate that signed [file], when Windows finds the signature valid. */
    private fun windowsPublisher(file: File): String? {
        val root = System.getenv("SystemRoot") ?: "C:\\Windows"
        val powershell = File(root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe").path
        // The path goes in through the environment, never into the command text
        val script = "\$s = Get-AuthenticodeSignature -LiteralPath \$env:$FILE_VARIABLE; " +
            "Write-Output \$s.Status; if (\$s.SignerCertificate) { Write-Output \$s.SignerCertificate.Subject }"
        val output = run(
            listOf(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script),
            mapOf(FILE_VARIABLE to file.absolutePath),
        ) ?: return null
        return parseAuthenticode(output)
    }

    /** The Developer ID team that signed [file] (an app or a disk image), when the signature verifies. */
    private fun macTeam(file: File): String? {
        val verified = run(listOf(CODESIGN, "--verify", "--strict", file.absolutePath), emptyMap()) ?: return null
        if (verified.exitCode != 0) return null
        val details = run(listOf(CODESIGN, "--display", "--verbose=2", file.absolutePath), emptyMap()) ?: return null
        return parseTeam(details)
    }

    companion object {
        private const val FILE_VARIABLE = "ZOUKAN_VERIFY_FILE"
        private const val CODESIGN = "/usr/bin/codesign"
        private const val TOOL_TIMEOUT_SECONDS = 120L

        /** First line the status, second the certificate subject; a publisher only when Valid. */
        internal fun parseAuthenticode(output: ToolOutput): String? {
            if (output.exitCode != 0) return null
            val lines = output.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size < 2 || lines[0] != "Valid") return null
            return lines[1]
        }

        /** `TeamIdentifier=ABCDE12345` in codesign's details; an ad hoc signature has "not set". */
        internal fun parseTeam(output: ToolOutput): String? {
            if (output.exitCode != 0) return null
            return output.text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("TeamIdentifier=") }
                ?.removePrefix("TeamIdentifier=")
                ?.takeIf { it.matches(Regex("[A-Z0-9]{10}")) }
        }

        /** The enclosing `.app` of a path inside an app bundle. */
        internal fun appBundle(executable: File): File? =
            generateSequence(executable.absoluteFile) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }

        private fun runTool(command: List<String>, environment: Map<String, String>): ToolOutput? = try {
            val process = ProcessBuilder(command).redirectErrorStream(true)
                .apply { environment().putAll(environment) }
                .start()
            process.outputWithin(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ?.let { ToolOutput(it.exitCode, it.text) }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not run ${command.first()}" }
            null
        }
    }
}
