package zoukan.extension

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * The SHA-256 of each extension's converted jar, recorded when it was converted from its verified
 * APK, together with the SHA-256 of that APK: the record binds the jar to the APK it came from, so
 * a jar never runs under a different APK than the one it was converted from. The jar itself is unsigned and sits in a folder any same-user process can write to, so the
 * hash is kept away from it: in the app's preferences, which on desktop are encrypted at rest under
 * a key in the OS secret store (FileCipher). A process that swaps the jar cannot also rewrite the
 * hash without that key.
 *
 * App-state keys, so backups neither carry nor restore them: a hash belongs to this computer's jar.
 */
interface ConvertedJarHashes {
    /**
     * Whether [jarSha256] is the hash recorded for the jar converted from the APK with [apkSha256],
     * for version [versionCode] of [pkgName].
     */
    fun matches(pkgName: String, versionCode: Long, apkSha256: String, jarSha256: String): Boolean

    fun record(pkgName: String, versionCode: Long, apkSha256: String, jarSha256: String)

    fun forget(pkgName: String)
}

class PreferenceConvertedJarHashes(private val preferenceStore: PreferenceStore) : ConvertedJarHashes {

    private fun preference(pkgName: String) =
        preferenceStore.getString(Preference.appStateKey("converted_jar_sha256_$pkgName"), "")

    override fun matches(pkgName: String, versionCode: Long, apkSha256: String, jarSha256: String): Boolean =
        preference(pkgName).get() == "$versionCode:$apkSha256:$jarSha256"

    override fun record(pkgName: String, versionCode: Long, apkSha256: String, jarSha256: String) {
        preference(pkgName).set("$versionCode:$apkSha256:$jarSha256")
    }

    override fun forget(pkgName: String) {
        preference(pkgName).delete()
    }
}
