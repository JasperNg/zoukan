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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The update check's state for the UI (Settings > About), the daily automatic check, and
 * installing a downloaded update.
 */
object Updates {
    val checker by lazy {
        UpdateChecker(Injekt.get<NetworkHelper>().client, Injekt.get<SecretStore>())
    }

    private val states by lazy {
        UpdateStates(
            check = { checker.check(includePrereleases().get()) },
            download = { asset, progress ->
                val dir = Injekt.get<ZoukanDirs>().updates
                // Only the newest download is kept
                dir.listFiles()?.filter { it.name != asset.name }?.forEach { it.deleteRecursively() }
                // Without GitHub's checksum the download cannot be told from a damaged or swapped one
                if (asset.sha256 == null) {
                    throw IOException("GitHub lists no checksum for ${asset.name}; get it from the release page instead")
                }
                checker.download(asset, dir, progress).also { file ->
                    val verdict = signature.verify(file)
                    if (verdict is InstallerSignature.Verdict.Rejected) {
                        file.delete()
                        throw IOException(verdict.reason)
                    }
                    logcat { "Update ${file.name}: $verdict" }
                }
            },
        )
    }

    private val signature by lazy { InstallerSignature() }

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
        // Again, right before it runs: the file sat in a folder other programs can write to
        (signature.verify(file) as? InstallerSignature.Verdict.Rejected)?.let {
            // Not kept: the same file would be rejected at every click, with no way to get another
            file.delete()
            states.installRejected(file, it.reason)
            throw IOException(it.reason)
        }
        when (Platform.current) {
            Platform.WINDOWS -> {
                ProcessBuilder("msiexec", "/i", file.absolutePath).start()
                quit()
            }
            Platform.MACOS -> ProcessBuilder("open", file.absolutePath).start()
            Platform.LINUX -> Unit
        }
    }

    private val CHECK_EVERY = 24.hours
}
