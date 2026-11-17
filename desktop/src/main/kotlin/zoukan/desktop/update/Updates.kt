package zoukan.desktop.update

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.Platform
import zoukan.desktop.ZoukanDirs
import zoukan.desktop.app.AppVersion
import zoukan.desktop.compat.localized
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.tray.Notifications
import zoukan.i18n.desktop.DMR
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The update check's state for the UI (Settings > About), the daily automatic check, and
 * installing a downloaded update.
 */
object Updates {
    private val gitHubChecker by lazy {
        UpdateChecker(Injekt.get<NetworkHelper>().client, Injekt.get<SecretStore>())
    }

    /**
     * Tests only: a checker for a local fixture release feed in place of GitHub's. Internal, set
     * by code, never by a setting; the downloads still need GitHub's API host and the release
     * signature (UpdateChecker.assetRequest, ReleaseSignature).
     */
    @Volatile
    internal var checkerForTests: UpdateChecker? = null

    val checker: UpdateChecker get() = checkerForTests ?: gitHubChecker

    private val states by lazy {
        UpdateStates(
            check = { checker.check(includePrereleases().get()) },
            download = { asset, progress ->
                downloadVerified(checker, asset, Injekt.get<ZoukanDirs>().updates, releaseSignature, signature, progress)
            },
        )
    }

    /**
     * Downloads [asset] into [dir] and checks it before it is kept: GitHub's SHA-256 (required),
     * the release signature with [releaseSignature] and the publisher's code signature with
     * [installerSignature]. A file that fails is deleted with its signature. Only the newest
     * download is kept in [dir].
     */
    internal suspend fun downloadVerified(
        checker: UpdateChecker,
        asset: UpdateChecker.Asset,
        dir: File,
        releaseSignature: ReleaseSignature,
        installerSignature: InstallerSignature,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        // Only the newest download is kept
        dir.listFiles()?.filter { it.name != asset.name && it.name != asset.name + ".sig" }?.forEach { it.deleteRecursively() }
        // Without GitHub's checksum the download cannot be told from a damaged or swapped one
        if (asset.sha256 == null) {
            throw IOException("GitHub lists no checksum for ${asset.name}; get it from the release page instead")
        }
        return checker.download(asset, dir, progress).also { file ->
            val sig = try {
                checker.downloadSignature(asset, dir)
            } catch (e: IOException) {
                file.delete()
                File(dir, asset.name + ".sig").delete()
                throw IOException("The release signature could not be downloaded: ${e.message}", e)
            }
            (releaseSignature.verify(file, sig) as? InstallerSignature.Verdict.Rejected)?.let {
                file.delete()
                sig?.delete()
                throw IOException(it.reason)
            }
            val verdict = installerSignature.verify(file)
            if (verdict is InstallerSignature.Verdict.Rejected) {
                file.delete()
                throw IOException(verdict.reason)
            }
            logcat { "Update ${file.name}: $verdict" }
        }
    }

    private val signature by lazy { InstallerSignature() }

    private val builtInReleaseSignature by lazy { ReleaseSignature.builtIn() }

    /**
     * Tests only: the release key's stand-in, so a journey can download and install an update it
     * signed with a test key. Internal, set by code, never by a setting; the shipped app always
     * checks with the key built into it (zoukan-release-key.pem).
     */
    @Volatile
    internal var releaseSignatureForTests: ReleaseSignature? = null

    private val releaseSignature: ReleaseSignature get() = releaseSignatureForTests ?: builtInReleaseSignature

    val state: StateFlow<UpdateStates.State> get() = states.state

    private val store get() = Injekt.get<PreferenceStore>()

    /** Check for updates once a day while the app runs. */
    fun autoCheck() = store.getBoolean("zoukan_update_check", true)

    fun includePrereleases() = store.getBoolean("zoukan_update_prereleases", false)

    fun lastCheck() = store.getLong(Preference.appStateKey("zoukan_update_last_check"), 0L)

    /** The newest version a notification was shown for, so each one is announced once */
    private fun notifiedVersion() = store.getString(Preference.appStateKey("zoukan_update_notified"), "")

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true || Platform.current == Platform.LINUX) return
        job = scope.launch {
            delay(30.seconds)
            while (isActive) {
                if (autoCheck().get() && System.currentTimeMillis() - lastCheck().get() >= CHECK_EVERY.inWholeMilliseconds) {
                    check(manual = false)
                }
                delay(30.minutes)
            }
        }
    }

    suspend fun check(manual: Boolean = true) {
        val result = states.check(manual) ?: return
        lastCheck().set(System.currentTimeMillis())
        when (result) {
            is UpdateChecker.Result.Available -> {
                val version = result.release.version.toString()
                if (!manual && notifiedVersion().get() != version) {
                    notifiedVersion().set(version)
                    Notifications.post(localized(MR.strings.update_check_notification_update_available), localized(DMR.strings.install_zoukan_version_from_about, version))
                }
            }
            is UpdateChecker.Result.Failed -> logcat(LogPriority.WARN) { "Update check failed: ${result.message}" }
            UpdateChecker.Result.UpToDate -> logcat { "Zoukan ${AppVersion.name} is up to date" }
        }
    }

    suspend fun download(release: UpdateChecker.Release) {
        states.download(release)
    }

    /**
     * Opens the installer. On Windows the MSI replaces the installed app, so Zoukan quits after
     * starting it ([quit]); on macOS the disk image opens for Zoukan to be dragged to Applications.
     * Throws when the installer is not signed by this app's publisher (see [InstallerSignature]).
     */
    fun install(file: File, quit: () -> Unit) {
        // Again, right before it runs: the file sat in a folder other programs can write to.
        // Check and run one private copy, so the file cannot be swapped between the check and the run.
        // The copy stays for the installer to read after Zoukan quits; the next download's cleanup removes it.
        val sigFile = File(file.parentFile, file.name + ".sig")
        val folder = Files.createTempDirectory(file.absoluteFile.parentFile.toPath(), "run", *privateDirAttributes())
        val copy = File(folder.toFile(), file.name)

        // Not kept: the same file would be rejected at every click, with no way to get another
        fun reject(reason: String): Nothing {
            folder.toFile().deleteRecursively()
            file.delete()
            sigFile.delete()
            states.installRejected(file, reason)
            throw IOException(reason)
        }
        try {
            Files.copy(file.toPath(), copy.toPath())
        } catch (e: IOException) {
            reject("The downloaded update could not be read (${e.message}). It was not opened.")
        }
        (releaseSignature.verify(copy, sigFile) as? InstallerSignature.Verdict.Rejected)?.let { reject(it.reason) }
        (signature.verify(copy) as? InstallerSignature.Verdict.Rejected)?.let { reject(it.reason) }
        installerCommand(Platform.current, copy)?.let { ProcessBuilder(it).start() }
        if (Platform.current == Platform.WINDOWS) quit()
    }

    private fun privateDirAttributes(): Array<FileAttribute<*>> =
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else {
            emptyArray()
        }

    internal fun installerCommand(
        platform: Platform,
        file: File,
        systemRoot: String? = System.getenv("SystemRoot"),
    ): List<String>? =
        when (platform) {
            Platform.WINDOWS -> listOf(File(systemRoot ?: "C:\\Windows", "System32\\msiexec.exe").path, "/i", file.absolutePath)
            Platform.MACOS -> listOf("/usr/bin/open", file.absolutePath)
            Platform.LINUX -> null
        }

    private val CHECK_EVERY = 24.hours
}
