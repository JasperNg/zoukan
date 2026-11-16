package zoukan.extension

import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.model.KOMIKKU_SIGNATURE
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getAndSet

/**
 * Komikku's trust rule (TrustExtension on Android): an extension is trusted when it is signed with
 * the signing key of an added extension store, or the user trusted this exact package, version and
 * signature. The trusted list is kept as app state (Preference.appStateKey), so it stays on this
 * computer: backups leave it out and restores drop it. An extension trusted by hand must be
 * trusted again after a library is moved to another computer.
 */
class ExtensionTrust(
    preferenceStore: PreferenceStore,
) {
    private val trustedExtensions = preferenceStore.getStringSet(
        Preference.appStateKey("trusted_extensions"),
        emptySet(),
    )

    fun isTrusted(
        pkgName: String,
        versionCode: Long,
        fingerprints: List<String>,
        stores: List<ExtensionStore>,
    ): Boolean {
        if (fingerprints.contains(KOMIKKU_SIGNATURE)) return true
        val storeKeys = stores.map { it.signingKey }.toHashSet()
        val key = "$pkgName:$versionCode:${fingerprints.last()}"
        return storeKeys.any { fingerprints.contains(it) } || key in trustedExtensions.get()
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        trustedExtensions.getAndSet { exts ->
            // Remove previously trusted versions
            exts.filterNot { it.startsWith("$pkgName:") }.toMutableSet().also {
                it += "$pkgName:$versionCode:$signatureHash"
            }
        }
    }

    fun revokeAll() {
        trustedExtensions.delete()
    }
}
