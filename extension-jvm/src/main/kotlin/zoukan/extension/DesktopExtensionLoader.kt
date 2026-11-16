package zoukan.extension

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import logcat.LogPriority
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.androidcompat.pm.ExternalPackages
import java.io.File

sealed interface LoadResult {
    val pkgName: String

    /**
     * @property classLoader the loader the extension's sources run in. Whoever keeps the extension
     * closes it when the extension is replaced or uninstalled (DesktopExtensionManager does).
     */
    data class Success(
        val extension: Extension.Installed,
        val classLoader: ExtensionClassLoader? = null,
    ) : LoadResult {
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
 * signature is then checked on the APK). The APK's signature does not cover the converted jar, so
 * it only runs while it matches the SHA-256 recorded in [hashes] when it was converted, which is
 * kept outside the extensions folder. The record also holds the SHA-256 of the APK the jar was
 * converted from, so a jar is tied to its APK: under any other APK it is converted again. A stale
 * or missing hash converts the verified APK again before the load. Nothing is recorded for a jar
 * that is untrusted or refused; it is converted again on each load until it is trusted.
 *
 * The jar is read into memory once: the signature or hash is checked on those bytes, and the same
 * bytes are what [ExtensionClassLoader] runs, so a jar swapped on disk after the check never runs,
 * and no jar stays open (or locked, on Windows) while its extension is loaded. Classes the host
 * provides (extensions-lib, OkHttp, the Android compatibility layer) come from the app's class path.
 *
 * As in ExtensionLoader, an NSFW extension is not loaded while [showNsfwSources] is off. It is read
 * once, at the first load, so changing the setting takes effect after a restart, as in Komikku.
 */
class DesktopExtensionLoader(
    private val extensionsDir: File,
    private val trust: ExtensionTrust,
    private val hashes: ConvertedJarHashes,
    private val showNsfwSources: () -> Boolean = { true },
) {
    /** The hashes go in the app's (encrypted) preferences. */
    constructor(extensionsDir: File, trust: ExtensionTrust, showNsfwSources: () -> Boolean = { true }) :
        this(extensionsDir, trust, PreferenceConvertedJarHashes(Injekt.get<PreferenceStore>()), showNsfwSources)

    private val loadNsfwSource by lazy { showNsfwSources() }

    init {
        extensionsDir.mkdirs()
        // Android's package manager knows the installed extensions: getPackageInfo(own package)
        ExternalPackages.provider = InstalledExtensionPackages(extensionsDir)
    }

    data class InstalledFiles(
        val pkgName: String,
        val versionCode: Long,
        val jar: File,
        /** The original APK, when the jar was converted from it. */
        val apk: File?,
        /**
         * The SHA-256 of the jar the installer just converted from [apk], to be recorded when it
         * loads. Null for a package found on disk, whose hash was recorded before.
         */
        val convertedSha256: String? = null,
    )

    /** The newest installed version of each package. Older versions left behind are deleted. */
    fun installedPackages(): List<InstalledFiles> = installedPackageNames().mapNotNull { installedPackage(it) }

    /** The names of the package folders, without reading them. A folder that is no package name is left alone. */
    fun installedPackageNames(): List<String> = extensionsDir.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.matches(PACKAGE_NAME) }
        .map { it.name }

    fun installedPackage(pkgName: String): InstalledFiles? {
        val dir = File(extensionsDir, pkgName)
        val jars = dir.listFiles { f -> f.isFile && f.extension == "jar" }.orEmpty()
            .mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { it to f } }
            .sortedByDescending { it.first }
        val (versionCode, jar) = jars.firstOrNull() ?: return null
        // A jar that was loaded stays locked on Windows until the app exits; it goes on a later start
        jars.drop(1).forEach { (code, _) -> deleteVersion(pkgName, code) }
        val apk = File(dir, "$versionCode.apk").takeIf { it.isFile }
        return InstalledFiles(pkgName, versionCode, jar, apk)
    }

    /**
     * Deletes version [versionCode] of [pkgName]: its jar, its APK, and the hash sidecar earlier
     * versions of Zoukan wrote. Used for older versions left behind, and for an update that
     * failed to load, so the next start loads the version that worked.
     */
    fun deleteVersion(pkgName: String, versionCode: Long) {
        val dir = checkedPackageDir(extensionsDir, pkgName)
        File(dir, "$versionCode.jar").delete()
        File(dir, "$versionCode.apk").delete()
        File(dir, "$versionCode.sha256").delete()
    }

    /** Forgets what is recorded about an uninstalled package. */
    fun forget(pkgName: String) {
        hashes.forget(pkgName)
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
        // The jar's bytes are read once; everything below checks and runs these bytes, never the
        // file again
        // The converted jar's hash to record once the load succeeds. There is one hash per
        // package: an update that fails to load must not replace the working version's hash
        var hashToRecord: String? = null
        var apkSha256 = ""
        val (signatures, contents) = if (files.apk != null) {
            val signers = ExtensionSignatures.verifyApk(files.apk)
            apkSha256 = ApkConverter.sha256Of(files.apk)
            // The manifest is read only after the checks that decide whether the jar may run at
            // all: a swapped jar must not fail the load before it was converted again, and then the
            // manifest of the healed jar is the one that counts
            val jar = verifiedConvertedJar(files, apkSha256)
            hashToRecord = jar.hashToRecord
            signers to JarContents.fromBytes(jar.bytes)
        } else {
            ExtensionSignatures.verifyJar(files.jar).let { it.signers to it.contents }
        }
        val manifest = ExtensionManifest.fromContents(contents, files.jar.name)
        if (manifest.packageName != files.pkgName) {
            throw ExtensionException(
                ExtensionException.Stage.MANIFEST,
                "${files.jar.name} declares package ${manifest.packageName}, not ${files.pkgName}",
            )
        }
        val libVersion = manifest.validate()
        val storeName = stores.firstOrNull { store -> signatures.all { it == store.signingKey } }
            ?.let { store -> store.badgeLabel.takeIf(String::isNotBlank) ?: store.name }

        if (!trust.isTrusted(manifest.packageName, manifest.versionCode, signatures, stores)) {
            logcat(LogPriority.WARN) { "Extension ${manifest.packageName} isn't trusted" }
            // Nothing is recorded: an untrusted jar is converted again on each load until it is trusted
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

        if (!loadNsfwSource && manifest.isNsfw) {
            logcat(LogPriority.WARN) { "NSFW extension ${manifest.packageName} not allowed" }
            // Like an untrusted one, nothing is recorded: the jar is converted again on each load
            return LoadResult.Error(
                manifest.packageName,
                ExtensionException(
                    ExtensionException.Stage.LOAD,
                    "NSFW extensions are not loaded while NSFW sources are hidden. Show NSFW sources " +
                        "in Browse settings and restart the app to load it.",
                ),
            )
        }

        val classLoader = ExtensionClassLoader(contents, manifest.packageName)
        try {
            val extension = installedExtension(manifest, libVersion, signatures, storeName, classLoader)
            hashToRecord?.let { record(files, apkSha256, it) }
            return LoadResult.Success(extension, classLoader)
        } catch (e: Throwable) {
            // A failed load keeps nothing: its sources were never handed out
            classLoader.close()
            throw e
        }
    }

    private fun installedExtension(
        manifest: ExtensionManifest,
        libVersion: Double,
        signatures: List<String>,
        storeName: String?,
        classLoader: ExtensionClassLoader,
    ): Extension.Installed {
        val sources = instantiateSources(classLoader, manifest)
        val langs = sources.map { it.lang }.toSet()
        return Extension.Installed(
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
    }

    /**
     * The bytes of the converted jar, once they match the hash recorded when it was converted. The
     * converted jar is the only unsigned part of an APK install, writable by anything that can
     * write the app's data. A jar the installer just converted carries its hash in [files]; a jar
     * found on disk must match the hash in [hashes]. A missing hash (an install made before the
     * hash moved out of the extensions folder) or a stale one converts the just signature-verified
     * APK again and records the new jar's hash; the conversion gets one attempt per load, and its
     * failure fails the load. Nothing is deleted on the way. A hash to record is returned, not
     * recorded: the caller records it only once the extension loaded.
     */
    private fun verifiedConvertedJar(files: InstalledFiles, apkSha256: String): VerifiedJar {
        val apk = checkNotNull(files.apk)
        val jar = files.jar
        val bytes = jar.readBytes()
        val sha256 = ApkConverter.sha256Of(bytes)
        if (files.convertedSha256 == sha256) return VerifiedJar(bytes, sha256)
        if (hashes.matches(files.pkgName, files.versionCode, apkSha256, sha256)) return VerifiedJar(bytes, null)
        logcat(LogPriority.WARN) {
            "Extension ${files.pkgName}: ${jar.name} has no matching recorded hash, converting ${apk.name} again"
        }
        ApkConverter.convert(apk, jar)
        val converted = jar.readBytes()
        return VerifiedJar(converted, ApkConverter.sha256Of(converted))
    }

    /** A converted jar's bytes, and the hash to record for them once the load succeeds (null: already recorded). */
    private class VerifiedJar(val bytes: ByteArray, val hashToRecord: String?)

    private fun record(files: InstalledFiles, apkSha256: String, jarSha256: String) {
        hashes.record(files.pkgName, files.versionCode, apkSha256, jarSha256)
        // The sidecar earlier versions wrote next to the jar; it is not read any more
        File(files.jar.parentFile, "${files.versionCode}.sha256").delete()
    }

    /** Instantiates the classes in tachiyomi.extension.class, the same way ExtensionLoader does. */
    private fun instantiateSources(classLoader: ExtensionClassLoader, manifest: ExtensionManifest): List<Source> {
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
