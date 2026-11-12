package zoukan.desktop.update

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import zoukan.desktop.Platform
import zoukan.desktop.outputWithin
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Checks who signed a downloaded installer before it is opened.
 *
 * The SHA-256 GitHub lists only shows the download is the file GitHub has; someone who can publish
 * a release (a stolen token) controls both. The code signature is made with a key that is not on
 * GitHub, so an update should be signed by the same publisher as the app that is running: the same
 * signer certificate public key on Windows, the same Developer ID team on macOS. That is the rule
 * Android applies to app updates. The public key, unlike the certificate's subject string, cannot
 * be matched by a differently-issued certificate carrying the same name, and unlike the
 * certificate's thumbprint it survives a renewal that reuses the key pair.
 *
 * The comparison only protects an app whose own signature can be read. The build records in its
 * build info ([signedRelease], written by the packaging) whether it shipped with a signing
 * certificate, so an installed build that says so but carries no readable signature fails closed:
 * its updates are [Verdict.Rejected] rather than a fall back to the checksum alone. A release
 * built without a certificate has no publisher to compare with; its updates are [Verdict.Unchecked]
 * and rest on the checksum alone, with a warning. The same is true of a development build (never
 * packaged) and of Linux, which has no code signatures. When the running app does carry a readable
 * signature it is compared whatever the build info says.
 */
class InstallerSignature(
    private val platform: Platform = Platform.current,
    private val packaged: Boolean = System.getProperty("zoukan.packaged") == "true",
    /** Whether the build shipped with a signing certificate, recorded in the build info it carries. */
    private val signedRelease: Boolean = Properties().apply {
        InstallerSignature::class.java.getResourceAsStream("/zoukan-build.properties")?.use { load(it) }
    }.getProperty("signedRelease") == "true",
    /** The running app: Zoukan.exe on Windows, a path inside Zoukan.app on macOS. */
    private val ownExecutable: () -> File? = {
        ProcessHandle.current().info().command().map(::File).orElse(null)
    },
    private val run: (command: List<String>, environment: Map<String, String>) -> ToolOutput? = { command, environment -> runTool(command, environment) },
) {
    data class ToolOutput(val exitCode: Int, val text: String)

    sealed interface Verdict {
        /** Signed by [publisher], the publisher of this app (its public key hash or team). */
        data class Trusted(val publisher: String) : Verdict

        /** This app has no publisher to compare with, so the download checksum is all there is. */
        data class Unchecked(val reason: String) : Verdict

        data class Rejected(val reason: String) : Verdict
    }

    fun verify(installer: File): Verdict {
        if (!packaged) {
            logcat(LogPriority.WARN) {
                "Update ${installer.name}: this build of Zoukan is not packaged, so there is no " +
                    "publisher signature to compare with and only the download checksum guards it"
            }
            return Verdict.Unchecked("this is not an installed build")
        }
        if (platform == Platform.LINUX) return Verdict.Unchecked("Linux builds carry no code signature")
        val own = ownExecutable()?.let { executable ->
            when (platform) {
                Platform.WINDOWS -> windowsKeyHash(executable)
                Platform.MACOS -> appBundle(executable)?.let(::macTeam)
                Platform.LINUX -> null
            }
        }
        if (own == null && !signedRelease) {
            logcat(LogPriority.WARN) {
                "Update ${installer.name}: this release was built without a code signing " +
                    "certificate, so only the download checksum guards it"
            }
            return Verdict.Unchecked("this release of Zoukan is not signed")
        }
        val theirs = when (platform) {
            Platform.WINDOWS -> windowsKeyHash(installer)
            Platform.MACOS -> macTeam(installer)
            Platform.LINUX -> null
        }
        return decide(own, theirs)
    }

    /** The signer certificate's public key for [file], hashed, when Windows finds the signature valid. */
    private fun windowsKeyHash(file: File): String? {
        val root = System.getenv("SystemRoot") ?: "C:\\Windows"
        val powershell = File(root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe").path
        // The path goes in through the environment, never into the command text
        val script = "\$s = Get-AuthenticodeSignature -LiteralPath \$env:$FILE_VARIABLE; " +
            "Write-Output \$s.Status; if (\$s.SignerCertificate) { Write-Output \$s.SignerCertificate.Thumbprint; " +
            "Write-Output (([System.Security.Cryptography.SHA256]::Create().ComputeHash(\$s.SignerCertificate.GetPublicKey()) | " +
            "ForEach-Object { \$_.ToString('x2') }) -join '') }"
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
        private val PUBLIC_KEY_HASH = Regex("[0-9a-fA-F]{64}")

        /**
         * First line the status, then the certificate thumbprint and the SHA-256 hash of the
         * signer's public key; the hash only when Valid. Updates compare the hash, not the
         * thumbprint: a renewed certificate that reuses the key pair has the same hash, and no
         * other publisher's certificate has it.
         */
        internal fun parseAuthenticode(output: ToolOutput): String? {
            if (output.exitCode != 0) return null
            val lines = output.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size < 3 || lines[0] != "Valid") return null
            return lines[2].takeIf { it.matches(PUBLIC_KEY_HASH) }
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

        /**
         * The verdict for the two signature descriptions: [own] from the running app, [theirs]
         * from the installer. Builds with no publisher to compare with never get here (see
         * [verify]), so a null own signature means a build that shipped signed has lost or
         * cannot show its signature: fail closed.
         */
        internal fun decide(own: String?, theirs: String?): Verdict = when {
            own == null -> Verdict.Rejected(
                "Zoukan's own code signature is missing or could not be read, so the installer's publisher could not be verified. It was not opened.",
            )
            theirs == null -> Verdict.Rejected("The installer is not signed, or its signature is not valid. It was not opened.")
            !theirs.equals(own, ignoreCase = true) ->
                Verdict.Rejected("The installer is signed by \"$theirs\", not by Zoukan's publisher \"$own\". It was not opened.")
            else -> Verdict.Trusted(own)
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
