package zoukan.extension

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import logcat.LogPriority
import mihon.domain.extension.model.ExtensionStore
import suwayomi.tachidesk.manga.impl.util.ChildFirstURLClassLoader
import tachiyomi.core.common.util.system.logcat
import java.io.File

sealed interface LoadResult {
    val pkgName: String

    data class Success(val extension: Extension.Installed) : LoadResult {
        override val pkgName get() = extension.pkgName
    }

    data class Untrusted(val extension: Extension.Untrusted) : LoadResult {
        override val pkgName get() = extension.pkgName
    }

    data class Error(override val pkgName: String, val error: ExtensionException) : LoadResult
}

/**
 * Loads installed extensions, the desktop counterpart of ExtensionLoader.
 *
 * Each extension lives in `<extensionsDir>/<package name>/`: `<versionCode>.jar` is the code that
 * runs, and `<versionCode>.apk` is kept next to it when the jar was converted from an APK (its
 * signature is then checked on the APK). The jar is loaded in its own child-first class loader;
 * classes the host provides (extensions-lib, OkHttp, the Android compatibility layer) come from the
 * app's class path.
 */
class DesktopExtensionLoader(
    private val extensionsDir: File,
    private val trust: ExtensionTrust,
) {
    init {
        extensionsDir.mkdirs()
    }

    data class InstalledFiles(
        val pkgName: String,
        val versionCode: Long,
        val jar: File,
        /** The original APK, when the jar was converted from it. */
        val apk: File?,
    )

    /** The newest installed version of each package. Older versions left behind are deleted. */
    fun installedPackages(): List<InstalledFiles> = extensionsDir.listFiles().orEmpty()
        .filter { it.isDirectory }
        .mapNotNull { installedPackage(it.name) }

    fun installedPackage(pkgName: String): InstalledFiles? {
        val dir = File(extensionsDir, pkgName)
        val jars = dir.listFiles { f -> f.isFile && f.extension == "jar" }.orEmpty()
            .mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { it to f } }
            .sortedByDescending { it.first }
        val (versionCode, jar) = jars.firstOrNull() ?: return null
        // A jar that was loaded stays locked on Windows until the app exits; it goes on a later start
        jars.drop(1).forEach { (code, old) ->
            old.delete()
            File(dir, "$code.apk").delete()
        }
        val apk = File(dir, "$versionCode.apk").takeIf { it.isFile }
        return InstalledFiles(pkgName, versionCode, jar, apk)
    }

    fun loadAll(stores: List<ExtensionStore>): List<LoadResult> = installedPackages().map { load(it, stores) }

    fun load(files: InstalledFiles, stores: List<ExtensionStore>): LoadResult {
        return try {
            loadOrThrow(files, stores)
        } catch (e: ExtensionException) {
            logcat(LogPriority.ERROR, e) { "Extension load error: ${files.pkgName}" }
            LoadResult.Error(files.pkgName, e)
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Extension load error: ${files.pkgName}" }
            LoadResult.Error(
                files.pkgName,
                ExtensionException(ExtensionException.Stage.LOAD, "${e.javaClass.simpleName}: ${e.message}", e),
            )
        }
    }

    private fun loadOrThrow(files: InstalledFiles, stores: List<ExtensionStore>): LoadResult {
        val manifest = ExtensionManifest.fromJar(files.jar)
        val libVersion = manifest.validate()
        val signatures = if (files.apk != null) {
            ExtensionSignatures.verifyApk(files.apk)
        } else {
            ExtensionSignatures.verifyJar(files.jar)
        }
        val storeName = stores.firstOrNull { store -> signatures.all { it == store.signingKey } }
            ?.let { store -> store.badgeLabel.takeIf(String::isNotBlank) ?: store.name }

        if (!trust.isTrusted(manifest.packageName, manifest.versionCode, signatures, stores)) {
            logcat(LogPriority.WARN) { "Extension ${manifest.packageName} isn't trusted" }
            return LoadResult.Untrusted(
                Extension.Untrusted(
                    name = manifest.name,
                    pkgName = manifest.packageName,
                    versionName = manifest.versionName,
                    versionCode = manifest.versionCode,
                    libVersion = libVersion,
                    signatureHash = signatures.last(),
                    storeName = storeName,
                ),
            )
        }

        val sources = instantiateSources(files.jar, manifest)
        val langs = sources.map { it.lang }.toSet()
        val extension = Extension.Installed(
            name = manifest.name,
            pkgName = manifest.packageName,
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            libVersion = libVersion,
            lang = when (langs.size) {
                0 -> ""
                1 -> langs.first()
                else -> "all"
            },
            isNsfw = manifest.isNsfw,
            signatureHash = signatures.last(),
            storeName = storeName,
            pkgFactory = manifest.factory,
            sources = sources,
            // TODO(Zoukan): an icon type for desktop; the store's icon URL serves the UI for now
            icon = null,
            isShared = false,
        )
        return LoadResult.Success(extension)
    }

    /** Instantiates the classes in tachiyomi.extension.class, the same way ExtensionLoader does. */
    private fun instantiateSources(jar: File, manifest: ExtensionManifest): List<Source> {
        val classLoader = ChildFirstURLClassLoader(arrayOf(jar.toURI().toURL()))
        return manifest.sourceClasses.flatMap { className ->
            try {
                when (val obj = Class.forName(className, false, classLoader).getDeclaredConstructor().newInstance()) {
                    is Source -> listOf(obj)
                    is SourceFactory -> obj.createSources()
                    else -> throw ExtensionException(
                        ExtensionException.Stage.LOAD,
                        "Unknown source class type: ${obj.javaClass}",
                    )
                }
            } catch (e: ExtensionException) {
                throw e
            } catch (e: Throwable) {
                throw ExtensionException(
                    ExtensionException.Stage.LOAD,
                    "$className: ${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
            }
        }
    }
}
