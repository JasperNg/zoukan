// ZOUKAN -->
package xyz.nulldev.androidcompat.io.sharedprefs

/**
 * How SharedPreferences files are kept on disk. The host app sets [current] before the first
 * SharedPreferences is opened; the desktop app encrypts the files with a key from the operating
 * system's secret store, since they hold tracker tokens, sync passwords and source logins.
 * Without one the files are plain XML, as in Suwayomi.
 */
interface SettingsFileCodec {
    /** Whether [stored] was written by [encode] (a plain file from before encryption was not). */
    fun isEncoded(stored: ByteArray): Boolean

    fun encode(plain: ByteArray): ByteArray

    /** Throws when [stored] cannot be read, for example with the wrong key. */
    fun decode(stored: ByteArray): ByteArray

    companion object {
        @Volatile
        @JvmStatic
        var current: SettingsFileCodec? = null
    }
}
// ZOUKAN <--
