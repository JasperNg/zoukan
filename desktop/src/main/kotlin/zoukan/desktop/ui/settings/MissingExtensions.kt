package zoukan.desktop.ui.settings

import eu.kanade.tachiyomi.extension.model.Extension
import zoukan.extension.DesktopExtensionManager
import zoukan.extension.LoadResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * A library source with no installed extension: its id, the name to show for it, and the
 * source's own name (without its language), which matches it to an untrusted extension no store offers.
 */
data class MissingSource(val id: Long, val name: String, val sourceName: String? = null)

/**
 * What "Install missing extensions" will do after a restore (desktop only; Android has no such
 * action): the extensions to install, with the missing sources each one brings; those already
 * installed but not trusted, which only the user can trust; and the sources no store offers.
 */
data class MissingExtensionsPlan(
    val toInstall: List<Candidate>,
    val needsTrust: List<Candidate>,
    val unavailable: List<MissingSource>,
    /**
     * Installed but untrusted extensions that no store offers. An untrusted extension's code never
     * runs, so its sources are unknown: a missing source goes to the one whose name matches it, and
     * the rest of these are still listed, as they may bring some of [unavailable].
     */
    val untrustedInstalled: List<UntrustedCandidate> = emptyList(),
) {
    data class Candidate(val extension: Extension.Available, val sources: List<MissingSource>)

    data class UntrustedCandidate(val extension: Extension.Untrusted, val sources: List<MissingSource>)

    /** The names of every extension that only the user can trust. */
    val needsTrustNames: List<String>
        get() = needsTrust.map { it.extension.name } + untrustedInstalled.map { it.extension.name }

    val isEmpty: Boolean
        get() = toInstall.isEmpty() && needsTrust.isEmpty() && unavailable.isEmpty() && untrustedInstalled.isEmpty()

    companion object {
        /**
         * Matches each of [missing] by source id to the store offers in [offers]. When several
         * stores offer one package, the copy the Browse screen lists counts
         * ([DesktopExtensionManager.chooseOffer]). An extension that is installed but untrusted
         * ([untrusted], by package name) is never installed again: the user must trust it. One
         * that is installed and loaded is offered only as an update.
         */
        fun of(
            missing: List<MissingSource>,
            offers: Collection<Extension.Available>,
            installed: Map<String, Extension.Installed>,
            untrusted: Map<String, Extension.Untrusted>,
        ): MissingExtensionsPlan {
            val chosen = offers.groupBy { it.pkgName }.mapNotNull { (pkg, list) ->
                val signer = installed[pkg]?.signatureHash ?: untrusted[pkg]?.signatureHash
                DesktopExtensionManager.chooseOffer(list, signer)
            }
            // A source id another package also claims goes to the first one, in the stores' order
            val bySource = LinkedHashMap<Long, Extension.Available>()
            chosen.forEach { ext -> ext.sources.forEach { bySource.putIfAbsent(it.id, ext) } }

            val toInstall = LinkedHashMap<String, MutableList<MissingSource>>()
            val needsTrust = LinkedHashMap<String, MutableList<MissingSource>>()
            val unavailable = mutableListOf<MissingSource>()
            val extensions = HashMap<String, Extension.Available>()
            val offered = offers.mapTo(HashSet()) { it.pkgName }
            val unoffered = untrusted.values.filter { it.pkgName !in offered }
            val byUntrusted = LinkedHashMap<String, MutableList<MissingSource>>()
            unoffered.forEach { byUntrusted[it.pkgName] = mutableListOf() }
            missing.distinctBy { it.id }.forEach { source ->
                val ext = bySource[source.id]
                val loaded = ext?.let { installed[it.pkgName] }
                when {
                    ext == null -> {
                        val match = unoffered.firstOrNull { matchesName(it.name, source.sourceName ?: source.name) }
                        if (match != null) byUntrusted.getValue(match.pkgName) += source else unavailable += source
                    }
                    ext.pkgName in untrusted -> needsTrust.getOrPut(ext.pkgName) { mutableListOf() } += source
                    // Installed without this source: only a newer version can bring it
                    loaded != null && ext.versionCode <= loaded.versionCode -> unavailable += source
                    else -> toInstall.getOrPut(ext.pkgName) { mutableListOf() } += source
                }
                if (ext != null) extensions[ext.pkgName] = ext
            }
            fun List<Pair<String, List<MissingSource>>>.candidates() = map { (pkg, sources) ->
                Candidate(extensions.getValue(pkg), sources)
            }.sortedBy { it.extension.name.lowercase() }
            return MissingExtensionsPlan(
                toInstall = toInstall.toList().candidates(),
                needsTrust = needsTrust.toList().candidates(),
                unavailable = unavailable.sortedBy { it.name.lowercase() },
                untrustedInstalled = unoffered.map { UntrustedCandidate(it, byUntrusted.getValue(it.pkgName)) }
                    .sortedBy { it.extension.name.lowercase() },
            )
        }

        /**
         * Whether an extension named [extensionName] (as "Tachiyomi: MangaDex", or just "MangaDex")
         * is the one of the source named [sourceName], ignoring case.
         */
        internal fun matchesName(extensionName: String, sourceName: String): Boolean {
            val name = extensionName.substringAfter(": ").trim()
            return name.isNotEmpty() && name.equals(sourceName.trim(), ignoreCase = true)
        }
    }
}

/** How installing the extensions of a [MissingExtensionsPlan] went. */
data class MissingExtensionsSummary(
    val installed: List<String>,
    /** Installed but signed with a key the user has not trusted, or already untrusted before. */
    val needsTrust: List<String>,
    /** Extension name to why it failed. */
    val failed: List<Pair<String, String>>,
    val unavailable: List<String>,
)

/**
 * Installs [plan]'s extensions one at a time through [install] (the extension manager's own
 * install, so every signature check, the signer pin and the untrusted flow apply). A failure is
 * recorded and the rest still install; cancellation stops the run.
 */
suspend fun installMissingExtensions(
    plan: MissingExtensionsPlan,
    install: suspend (Extension.Available) -> LoadResult,
    onProgress: (done: Int, total: Int, current: String?) -> Unit = { _, _, _ -> },
): MissingExtensionsSummary {
    val installed = mutableListOf<String>()
    val needsTrust = plan.needsTrustNames.toMutableList()
    val failed = mutableListOf<Pair<String, String>>()
    val total = plan.toInstall.size
    plan.toInstall.forEachIndexed { index, candidate ->
        val ext = candidate.extension
        onProgress(index, total, ext.name)
        try {
            when (val result = install(ext)) {
                is LoadResult.Success -> installed += ext.name
                is LoadResult.Untrusted -> needsTrust += ext.name
                is LoadResult.Error -> failed += ext.name to (result.error.message ?: result.error.stage.name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed += ext.name to (e.message ?: e::class.simpleName.orEmpty())
        }
    }
    onProgress(total, total, null)
    return MissingExtensionsSummary(
        installed = installed,
        needsTrust = needsTrust,
        failed = failed,
        unavailable = plan.unavailable.map { it.name },
    )
}
