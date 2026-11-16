package zoukan.extension

import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import xyz.nulldev.androidcompat.pm.ExternalPackages
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The installed extensions as Android's package manager reports them (getPackageInfo of an
 * extension's own package, getInstalledPackages), read from the extensions folder when asked: the
 * newest `<versionCode>.jar` of each package folder and the manifest it carries. Reading only, so
 * an uninstall or a failed update shows at once. Only the manifest entry is read, and each jar's
 * result is kept while the file is unchanged.
 */
class InstalledExtensionPackages(private val extensionsDir: File) : ExternalPackages.Provider {

    private data class Key(val path: String, val modified: Long, val length: Long)

    private val cache = ConcurrentHashMap<String, Pair<Key, PackageInfo?>>()

    override fun find(packageName: String): PackageInfo? {
        if (!packageName.matches(PACKAGE_NAME)) return null
        val jar = newestJar(File(extensionsDir, packageName)) ?: return null
        val key = Key(jar.path, jar.lastModified(), jar.length())
        cache[packageName]?.let { (cached, info) -> if (cached == key) return info?.copy() }
        val info = runCatching { ExtensionManifest.fromJar(jar) }.getOrNull()
            ?.takeIf { it.packageName == packageName }
            ?.toPackageInfo(jar)
        cache[packageName] = key to info
        return info?.copy()
    }

    override fun all(): List<PackageInfo> = extensionsDir.listFiles().orEmpty()
        .filter { it.isDirectory }
        .mapNotNull { find(it.name) }

    private fun newestJar(dir: File): File? = dir.listFiles { f -> f.isFile && f.extension == "jar" }.orEmpty()
        .mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { it to f } }
        .maxByOrNull { it.first }
        ?.second

    /** A fresh object per call: callers may change what they get, as with Android's binder copies. */
    private fun PackageInfo.copy(): PackageInfo = PackageInfo().also {
        it.packageName = packageName
        it.versionName = versionName
        it.setLongVersionCode(longVersionCode)
        it.reqFeatures = reqFeatures?.clone()
        it.applicationInfo = ApplicationInfo(applicationInfo)
        it.applicationInfo.metaData = applicationInfo.metaData?.let { Bundle(it) }
    }

    private fun ExtensionManifest.toPackageInfo(jar: File): PackageInfo {
        val manifest = this
        return PackageInfo().also { info ->
            info.packageName = manifest.packageName
            info.versionName = manifest.versionName
            info.setLongVersionCode(manifest.versionCode)
            info.reqFeatures = manifest.features.map { FeatureInfo().apply { name = it } }.toTypedArray()
            info.applicationInfo = ApplicationInfo().also { app ->
                app.packageName = manifest.packageName
                app.nonLocalizedLabel = manifest.label
                app.sourceDir = jar.absolutePath
                app.metaData = Bundle().also { bundle -> manifest.metaData.forEach { (k, v) -> bundle.putString(k, v) } }
            }
        }
    }
}
