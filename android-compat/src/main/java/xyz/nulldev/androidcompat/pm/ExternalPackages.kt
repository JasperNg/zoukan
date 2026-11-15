package xyz.nulldev.androidcompat.pm

import android.content.pm.PackageInfo

// ZOUKAN: whole file

/**
 * What the emulated package manager knows beyond AndroidCompat's own package folder: the app
 * itself, and the extensions the desktop app installs in its own extensions folder. The desktop
 * app fills this (the extension loader sets [provider], the runtime sets the app's version), so
 * android-compat does not depend on it; FakePackageManager reads it.
 */
object ExternalPackages {
    /** Installed packages, looked up when an extension asks for them. */
    interface Provider {
        /** The installed package [packageName], or null when it is not installed. */
        fun find(packageName: String): PackageInfo?

        /** Every installed package. */
        fun all(): List<PackageInfo>
    }

    @Volatile
    @JvmStatic
    var provider: Provider? = null

    /** The app's version, as its PackageInfo reports it. */
    @Volatile
    @JvmStatic
    var appVersionName: String = "0.0.0"

    @Volatile
    @JvmStatic
    var appVersionCode: Long = 0

    /** The app's own PackageInfo, for [packageName]. */
    @JvmStatic
    fun appPackageInfo(packageName: String): PackageInfo =
        PackageInfo().also {
            it.packageName = packageName
            it.versionName = appVersionName
            it.setLongVersionCode(appVersionCode)
            it.applicationInfo =
                android.content.pm.ApplicationInfo().apply {
                    this.packageName = packageName
                }
        }
}
