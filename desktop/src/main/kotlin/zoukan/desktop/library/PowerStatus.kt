package zoukan.desktop.library

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import zoukan.desktop.Platform
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Whether the computer runs on mains power, for the "Charging" library update restriction
 * (Android's DEVICE_CHARGING). A desktop without a battery always is; when the state cannot be
 * read, the update is not held back.
 */
object PowerStatus {
    fun onMainsPower(): Boolean = runCatching {
        when (Platform.current) {
            Platform.WINDOWS -> windows()
            Platform.MACOS -> mac()
            else -> linux()
        }
    }.onFailure { logcat(LogPriority.WARN, it) { "Reading the power status failed" } }.getOrNull() ?: true

    private fun windows(): Boolean? {
        val status = SystemPowerStatus()
        if (!PowerKernel32.INSTANCE.GetSystemPowerStatus(status)) return null
        // ACLineStatus: 0 battery, 1 mains, 255 unknown
        return when (status.ACLineStatus.toInt() and 0xFF) {
            0 -> false
            1 -> true
            else -> null
        }
    }

    private fun mac(): Boolean? =
        parsePmset(readOutput(ProcessBuilder("pmset", "-g", "ps").redirectErrorStream(true).start(), timeoutMillis = 5_000))

    /** The process's output, or null when it does not end within the timeout. */
    internal fun readOutput(process: Process, timeoutMillis: Long): String? {
        // Read on another thread: a read on this one blocks until the process ends, so the wait
        // below would never get to apply its timeout.
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            logcat(LogPriority.WARN) { "Reading the power status timed out" }
            process.destroyForcibly()
            output.cancel(true)
            return null
        }
        return runCatching { output.get(1, TimeUnit.SECONDS) }.getOrNull()
    }

    internal fun parsePmset(out: String?): Boolean? = when {
        out == null -> null
        "AC Power" in out -> true
        "Battery Power" in out -> false
        else -> null
    }

    private fun linux(): Boolean? {
        val supplies = java.io.File("/sys/class/power_supply").listFiles() ?: return null
        val mains = supplies.filter { java.io.File(it, "type").readTextOrNull()?.trim() == "Mains" }
        if (mains.isEmpty()) return null
        return mains.any { java.io.File(it, "online").readTextOrNull()?.trim() == "1" }
    }

    private fun java.io.File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    @Suppress("FunctionName")
    private interface PowerKernel32 : StdCallLibrary {
        fun GetSystemPowerStatus(status: SystemPowerStatus): Boolean

        companion object {
            val INSTANCE: PowerKernel32 by lazy { Native.load("kernel32", PowerKernel32::class.java) }
        }
    }

    /** Win32 SYSTEM_POWER_STATUS */
    @Suppress("PropertyName")
    @Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
    class SystemPowerStatus : Structure() {
        @JvmField var ACLineStatus: Byte = 0

        @JvmField var BatteryFlag: Byte = 0

        @JvmField var BatteryLifePercent: Byte = 0

        @JvmField var SystemStatusFlag: Byte = 0

        @JvmField var BatteryLifeTime: Int = 0

        @JvmField var BatteryFullLifeTime: Int = 0
    }
}
