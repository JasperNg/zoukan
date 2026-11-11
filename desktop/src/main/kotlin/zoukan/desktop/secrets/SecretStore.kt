package zoukan.desktop.secrets

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import zoukan.desktop.Platform
import java.io.File

/**
 * Small secrets (the database key, the password for encrypted CBZ downloads) in the operating
 * system's secret store: Windows Credential Manager or the macOS Keychain. It takes the place of
 * the Android Keystore, which CbzCrypto uses on Android.
 */
interface SecretStore {
    /** A human readable name, for logs and reports. */
    val name: String

    fun get(key: String): String?

    fun set(key: String, value: String)

    fun delete(key: String)

    companion object {
        /** The service name entries are stored under. */
        const val SERVICE = "Zoukan"

        /**
         * The store for this platform. On Linux, which Zoukan only supports for development, the
         * secrets go to a file readable only by the user, with a warning.
         */
        fun forPlatform(fallbackDir: File): SecretStore = when {
            // Throwaway profiles (the backup round-trip check, tests) keep their key in the profile,
            // so they never touch the user's key in the OS store
            System.getProperty(FILE_STORE_PROPERTY) == "true" -> FileSecretStore(fallbackDir)
            else -> forOs(fallbackDir)
        }

        /** Set to "true" to keep secrets in a file in the profile instead of the OS store. */
        const val FILE_STORE_PROPERTY = "zoukan.fileSecrets"

        private fun forOs(fallbackDir: File): SecretStore = when (Platform.current) {
            Platform.WINDOWS -> WindowsCredentialStore()
            Platform.MACOS -> MacKeychainStore()
            Platform.LINUX -> {
                logcat(LogPriority.WARN) { "No OS secret store on Linux; secrets are kept in $fallbackDir" }
                FileSecretStore(fallbackDir)
            }
        }
    }
}
