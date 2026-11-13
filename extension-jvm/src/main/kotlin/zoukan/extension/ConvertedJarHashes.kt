package zoukan.extension

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * The SHA-256 of each extension's converted jar, recorded when it was converted from its verified
 * APK. The jar itself is unsigned and sits in a folder any same-user process can write to, so the
 * hash is kept away from it: in the app's preferences, which on desktop are encrypted at rest under
 * a key in the OS secret store (FileCipher). A process that swaps the jar cannot also rewrite the
 * hash without that key.
 *
 * App-state keys, so backups neither carry nor restore them: a hash belongs to this computer's jar.
 */
interface ConvertedJarHashes {
    /** Whether [sha256] is the hash recorded for version [versionCode] of [pkgName]. */
    fun matches(pkgName: String, versionCode: Long, sha256: String): Boolean

    fun record(pkgName: String, versionCode: Long, sha256: String)

    fun forget(pkgName: String)
}

class PreferenceConvertedJarHashes(private val preferenceStore: PreferenceStore) : ConvertedJarHashes {

    private fun preference(pkgName: String) =
        preferenceStore.getString(Preference.appStateKey("converted_jar_sha256_$pkgName"), "")

    override fun matches(pkgName: String, versionCode: Long, sha256: String): Boolean =
        preference(pkgName).get() == "$versionCode:$sha256"

    override fun record(pkgName: String, versionCode: Long, sha256: String) {
        preference(pkgName).set("$versionCode:$sha256")
    }

    override fun forget(pkgName: String) {
        preference(pkgName).delete()
    }
}
