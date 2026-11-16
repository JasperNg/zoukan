package zoukan.extension

import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

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

    /** Files an install started after this are newer than this, and are not leftovers. */
    private val createdAt = System.currentTimeMillis()

    /** Loads every installed extension. */
    suspend fun init() = withContext(Dispatchers.IO) {
        try {
            try {
                loader.removeLeftovers(createdAt)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not remove leftover extension files" }
            }
            val stores = getStores()
            loader.installedPackageNames().forEach { pkg ->
                locked(pkg) {
                    // Read again under the lock: an uninstall may have run since the folder was listed
                    // A reinstall that never confirmed (a crash): its kept-aside files come back first
                    try {
                        loader.restoreInterruptedReinstall(pkg)
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "Could not restore the files of $pkg" }
                    }
                    loader.installedPackage(pkg)?.let { register(loader.load(it, stores)) }
                }
            }
        } finally {
            // Also after a failure: the library update, automatic backups and the download queue
            // wait for this, and would otherwise never start
            _isInitialized.value = true
        }
    }

    /** Fetches the extension lists of all added stores. */
    suspend fun findAvailableExtensions(): Map<ExtensionStore, Result<List<Extension.Available>>> =
        withContext(Dispatchers.IO) {
            // A store removed while this fetches must not have its offers put back
            val forgottenBefore = synchronized(stateLock) { forgottenStores.size }
            val results = getStores().associateWith { storeService.getExtensions(it) }
            results.forEach { (store, result) ->
                result.onFailure { logcat(LogPriority.ERROR, it) { "Failed to get extensions of ${store.name}" } }
            }
            // Stores in the order they were added. When two offer one package, the copy signed like
            // the installed one counts; never simply the last store's (see chooseOffer)
            synchronized(stateLock) {
                val forgotten = forgottenStores.subList(forgottenBefore, forgottenStores.size).toSet()
                offers = results
                    .filterKeys { it.indexUrl !in forgotten }
                    .values
                    .flatMap { it.getOrDefault(emptyList()) }
                    .groupBy { it.pkgName }
                chooseOffers()
            }
            results
        }

    /**
     * Drops the offers of the removed store at [indexUrl], so its extensions are no longer listed
     * or installable, and an installed extension it updated loses that update.
     */
    fun forgetStore(indexUrl: String) {
        synchronized(stateLock) {
            forgottenStores += indexUrl
            offers = offers
                .mapValues { (_, list) -> list.filterNot { it.store.indexUrl == indexUrl } }
                .filterValues { it.isNotEmpty() }
            chooseOffers()
        }
    }

    /**
     * Installs or updates [extension] and loads it. An update whose new files fail to load leaves
     * the working version installed and listed: its new files are removed, so the next start loads
     * the working version too, and the error is returned for the caller to report. A reinstall of
     * the same version keeps the working files aside until the new ones load, and puts them back
     * if they do not.
     */
    suspend fun installExtension(extension: Extension.Available, forceApk: Boolean = false): LoadResult =
        withContext(Dispatchers.IO) {
            val pkg = extension.pkgName
            locked(pkg) {
                val previous = loader.installedPackage(pkg)
                val done = try {
                    // An update must be signed like the version it replaces, as Android's package
                    // manager demands; otherwise any added store could replace an installed extension.
                    // And it must not be older, as Android refuses a downgrade
                    installer.install(
                        extension,
                        forceApk,
                        requiredSigner = requiredSigner(pkg),
                        installedVersionCode = previous?.versionCode,
                    )
                } catch (e: ExtensionException) {
                    // Refused before anything was replaced: the installed version stays listed (and
                    // pinned) as it was. The caller reports the error
                    return@withContext LoadResult.Error(pkg, e)
                }
                val files = done.files
                val result = try {
                    loader.load(files, getStores())
                } catch (e: Throwable) {
                    // Also when cancelled: a same-version reinstall's working files come back
                    installer.restorePrevious(done)
                    throw e
                }
                if (result is LoadResult.Error) {
                    if (installed.value[pkg] != null && previous != null && previous.versionCode != files.versionCode) {
                        loader.deleteVersion(pkg, files.versionCode)
                    } else {
                        // A same-version reinstall that does not load: what the user had stays, byte
                        // for byte (a no-op when nothing was kept aside)
                        installer.restorePrevious(done)
                    }
                    if (installed.value[pkg] != null) return@withContext result
                } else {
                    installer.dropPrevious(done)
                }
                register(result)
                result
            }
        }

    suspend fun updateExtension(extension: Extension.Installed): LoadResult? {
        val update = available.value[extension.pkgName] ?: return null
        return installExtension(update)
    }

    /**
     * Uninstalls [pkgName] once any install or trust of it running now has finished, so it cannot
     * come back. Starts the work and returns at once, for callers on the UI thread; see [uninstall].
     */
    fun uninstallExtension(pkgName: String): Job = scope.launch {
        try {
            uninstall(pkgName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to uninstall $pkgName" }
        }
    }

    /** Uninstalls [pkgName], waiting for any install or trust of it running now. */
    suspend fun uninstall(pkgName: String) = withContext(Dispatchers.IO) {
        locked(pkgName) {
            installer.uninstall(pkgName)
            loader.forget(pkgName)
            val old = synchronized(stateLock) {
                untrusted.update { it - pkgName }
                errors.update { it - pkgName }
                installed.update { it - pkgName }
                chooseOffers()
                classLoaders.remove(pkgName)
            }
            // After the sources left the lists, so nothing new picks them up
            old?.close()
        }
    }

    /** Trusts this version and signature of an untrusted extension, then loads it. */
    suspend fun trust(extension: Extension.Untrusted): LoadResult? = withContext(Dispatchers.IO) {
        locked(extension.pkgName) {
            trust.trust(extension.pkgName, extension.versionCode, extension.signatureHash)
            val files = loader.installedPackage(extension.pkgName) ?: return@withContext null
            loader.load(files, getStores()).also(::register)
        }
    }

    /** One lock per package: its install, update, uninstall and trust run one at a time. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private suspend inline fun <T> locked(pkgName: String, block: () -> T): T =
        locks.computeIfAbsent(pkgName) { Mutex() }.withLock { block() }

    /** Guards changes to the lists, so a package is never in two of them at once. */
    private val stateLock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The class loader of each loaded extension, closed when it is replaced or uninstalled. */
    private val classLoaders = ConcurrentHashMap<String, ExtensionClassLoader>()

    /**
     * Lists [result] in place of whatever [pkgName][LoadResult.pkgName] had, then closes the class
     * loader it replaces: only once the new state is in place, so the package never lacks a
     * working source in between.
     */
    private fun register(result: LoadResult) {
        val old = synchronized(stateLock) {
            updateLists(result)
            val new = (result as? LoadResult.Success)?.classLoader
            if (new != null) classLoaders.put(result.pkgName, new) else classLoaders.remove(result.pkgName)
        }
        if (old != null && old !== (result as? LoadResult.Success)?.classLoader) old.close()
    }

    private fun updateLists(result: LoadResult) {
        val pkg = result.pkgName
        // Removed from every list before it is added to one, so no reader sees it twice
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

    /**
     * The signer an install of [pkgName] must match: that of the copy installed on disk, which
     * stays there when it fails to load or an update of it is refused, so the pin survives both.
     * A loaded copy's signer was read from those same files and saves verifying them again. An
     * installed copy whose signature no longer verifies cannot be replaced, only uninstalled.
     */
    private fun requiredSigner(pkgName: String): String? {
        installedSigner(pkgName)?.let { return it }
        val files = loader.installedPackage(pkgName) ?: return null
        return try {
            val signers = if (files.apk != null) {
                ExtensionSignatures.verifyApk(readInstalledApk(files.apk))
            } else {
                ExtensionSignatures.verifyJar(files.jar).signers
            }
            signers.lastOrNull() ?: throw ExtensionException(ExtensionException.Stage.SIGNATURE, "No signer")
        } catch (e: Exception) {
            throw ExtensionException(
                ExtensionException.Stage.SIGNATURE,
                "The installed version of $pkgName could not be verified, so it was not replaced. " +
                    "Uninstall the extension first.",
                e,
            )
        }
    }

    /** Every store's offer of each package, from the last [findAvailableExtensions]. */
    @Volatile
    private var offers = emptyMap<String, List<Extension.Available>>()

    /** Every store [forgetStore] removed, in order; a fetch drops those removed while it ran. */
    // Guarded by stateLock
    private val forgottenStores = mutableListOf<String>()

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
            // No store offers it any more (one was removed): no update, and no store to update from
            ?: return copy(hasUpdate = false, store = null)
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
