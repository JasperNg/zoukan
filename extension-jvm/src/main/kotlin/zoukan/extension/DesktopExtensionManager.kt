package zoukan.extension

import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.core.common.util.system.logcat

/**
 * The desktop counterpart of ExtensionManager: keeps the lists of installed, untrusted and
 * available extensions, and installs, updates, trusts and uninstalls them.
 */
class DesktopExtensionManager(
    private val loader: DesktopExtensionLoader,
    private val installer: DesktopExtensionInstaller,
    private val trust: ExtensionTrust,
    private val storeService: ExtensionStoreService,
    /** The added extension stores (GetExtensionStores on the app's database). */
    private val getStores: suspend () -> List<ExtensionStore>,
) {
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val installed = MutableStateFlow(emptyMap<String, Extension.Installed>())
    val installedExtensionsFlow: StateFlow<Map<String, Extension.Installed>> = installed.asStateFlow()

    private val untrusted = MutableStateFlow(emptyMap<String, Extension.Untrusted>())
    val untrustedExtensionsFlow: StateFlow<Map<String, Extension.Untrusted>> = untrusted.asStateFlow()

    private val available = MutableStateFlow(emptyMap<String, Extension.Available>())
    val availableExtensionsFlow: StateFlow<Map<String, Extension.Available>> = available.asStateFlow()

    private val errors = MutableStateFlow(emptyMap<String, ExtensionException>())

    /** Why an installed package failed to load, by package name. */
    val loadErrorsFlow: StateFlow<Map<String, ExtensionException>> = errors.asStateFlow()

    /** Loads every installed extension. */
    suspend fun init() = withContext(Dispatchers.IO) {
        try {
            val stores = getStores()
            loader.loadAll(stores).forEach(::register)
        } finally {
            // Also after a failure: the library update, automatic backups and the download queue
            // wait for this, and would otherwise never start
            _isInitialized.value = true
        }
    }

    /** Fetches the extension lists of all added stores. */
    suspend fun findAvailableExtensions(): Map<ExtensionStore, Result<List<Extension.Available>>> =
        withContext(Dispatchers.IO) {
            val results = getStores().associateWith { storeService.getExtensions(it) }
            results.forEach { (store, result) ->
                result.onFailure { logcat(LogPriority.ERROR, it) { "Failed to get extensions of ${store.name}" } }
            }
            // Stores in the order they were added. When two offer one package, the copy signed like
            // the installed one counts; never simply the last store's (see chooseOffer)
            offers = results.values.flatMap { it.getOrDefault(emptyList()) }.groupBy { it.pkgName }
            chooseOffers()
            results
        }

    /** Installs or updates [extension] and loads it. */
    suspend fun installExtension(extension: Extension.Available, forceApk: Boolean = false): LoadResult =
        withContext(Dispatchers.IO) {
            val result = try {
                // An update must be signed like the version it replaces, as Android's package
                // manager demands; otherwise any added store could replace an installed extension
                val files = installer.install(extension, forceApk, requiredSigner = installedSigner(extension.pkgName)).files
                loader.load(files, getStores())
            } catch (e: ExtensionException) {
                LoadResult.Error(extension.pkgName, e)
            }
            register(result)
            result
        }

    suspend fun updateExtension(extension: Extension.Installed): LoadResult? {
        val update = available.value[extension.pkgName] ?: return null
        return installExtension(update)
    }

    fun uninstallExtension(pkgName: String) {
        installer.uninstall(pkgName)
        installed.update { it - pkgName }
        untrusted.update { it - pkgName }
        errors.update { it - pkgName }
        chooseOffers()
    }

    /** Trusts this version and signature of an untrusted extension, then loads it. */
    suspend fun trust(extension: Extension.Untrusted): LoadResult? = withContext(Dispatchers.IO) {
        trust.trust(extension.pkgName, extension.versionCode, extension.signatureHash)
        val files = loader.installedPackage(extension.pkgName) ?: return@withContext null
        loader.load(files, getStores()).also(::register)
    }

    private fun register(result: LoadResult) {
        val pkg = result.pkgName
        installed.update { it - pkg }
        untrusted.update { it - pkg }
        errors.update { it - pkg }
        when (result) {
            is LoadResult.Success -> installed.update { it + (pkg to result.extension.withUpdateCheck()) }
            is LoadResult.Untrusted -> untrusted.update { it + (pkg to result.extension) }
            is LoadResult.Error -> errors.update { it + (pkg to result.error) }
        }
        if (offers.isNotEmpty()) chooseOffers()
    }

    /** The signing certificate of the installed (or installed but untrusted) copy of [pkgName]. */
    private fun installedSigner(pkgName: String): String? =
        installed.value[pkgName]?.signatureHash ?: untrusted.value[pkgName]?.signatureHash

    /** Every store's offer of each package, from the last [findAvailableExtensions]. */
    @Volatile
    private var offers = emptyMap<String, List<Extension.Available>>()

    /** Again after an install or uninstall: which store's copy counts depends on what is installed. */
    private fun chooseOffers() {
        available.value = offers.mapNotNull { (pkg, list) -> chooseOffer(list, installedSigner(pkg)) }
            .associateBy { it.pkgName }
        markUpdates()
    }

    private fun markUpdates() {
        installed.update { map -> map.mapValues { (_, ext) -> ext.withUpdateCheck() } }
    }

    private fun Extension.Installed.withUpdateCheck(): Extension.Installed {
        val availableExt = available.value[pkgName]
            // Only a store that signs with this extension's key can update it
            ?.takeIf { it.store.signingKey == signatureHash || it.store.signingKey == NO_SIGNING_KEY }
            ?: return copy(hasUpdate = false)
        return copy(
            hasUpdate = availableExt.versionCode > versionCode || availableExt.libVersion > libVersion,
            store = availableExt.store,
        )
    }

    companion object {
        /** What a legacy store, which names no signing key, has as its key. */
        const val NO_SIGNING_KEY = "NO_SIGNING_KEY"

        /**
         * Which of several stores' [offers] of one package the app lists. With the package
         * installed and signed by [installedSigner], that is the offer of a store signing with that
         * key, else one of a store that names no key (its file is still checked against the
         * installed signer when it installs), else none: another store's copy cannot update it.
         * Not installed: the first store's, in the order the stores were added.
         */
        fun chooseOffer(offers: List<Extension.Available>, installedSigner: String?): Extension.Available? {
            if (installedSigner == null) return offers.firstOrNull()
            return offers.firstOrNull { it.store.signingKey == installedSigner }
                ?: offers.firstOrNull { it.store.signingKey == NO_SIGNING_KEY }
        }
    }
}
