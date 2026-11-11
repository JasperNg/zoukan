package zoukan.extension

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.data.extension.service.requireSecureStoreUrl
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Installs an extension from its store. Keiyoushi publishes a ready-made, JAR-signed JVM jar of
 * each extension (the store's jarUrl), which is installed as it is. For a store that publishes only
 * APKs, the APK is converted with dex2jar.
 *
 * The signature and manifest are checked before anything is installed; whether the signer is
 * trusted is decided when the extension loads, as on Android.
 */
class DesktopExtensionInstaller(
    private val extensionsDir: File,
    private val client: () -> OkHttpClient,
) {
    /** How the extension was installed. */
    enum class Method { JAR, APK }

    data class Installed(val files: DesktopExtensionLoader.InstalledFiles, val method: Method)

    /**
     * @param requiredSigner the signing certificate's SHA-256 of the installed version, when this
     * is an update: a file signed by anyone else is refused before it replaces anything.
     */
    suspend fun install(
        extension: Extension.Available,
        forceApk: Boolean = false,
        requiredSigner: String? = null,
    ): Installed =
        withContext(Dispatchers.IO) {
            val jarUrl = extension.jarUrl
            if (jarUrl != null && !forceApk) {
                installJar(extension, jarUrl, requiredSigner)
            } else {
                installApk(extension, requiredSigner)
            }
        }

    private fun checkSigner(signers: List<String>, requiredSigner: String?, extension: Extension.Available) {
        if (requiredSigner == null || signers.lastOrNull() == requiredSigner) return
        throw ExtensionException(
            ExtensionException.Stage.SIGNATURE,
            "This update of ${extension.name} is signed with a different key than the installed version, so it " +
                "was not installed. To switch to this store's version, uninstall the extension first.",
        )
    }

    private suspend fun installJar(extension: Extension.Available, url: String, requiredSigner: String?): Installed {
        val tmp = download(url, extension.pkgName, ".jar")
        try {
            checkSigner(ExtensionSignatures.verifyJar(tmp), requiredSigner, extension)
            val manifest = ExtensionManifest.fromJar(tmp).also { checkManifest(it, extension) }
            val dir = packageDir(extension.pkgName)
            val jar = File(dir, "${manifest.versionCode}.jar")
            Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            File(dir, "${manifest.versionCode}.apk").delete()
            return Installed(
                DesktopExtensionLoader.InstalledFiles(manifest.packageName, manifest.versionCode, jar, null),
                Method.JAR,
            )
        } finally {
            tmp.delete()
        }
    }

    private suspend fun installApk(extension: Extension.Available, requiredSigner: String?): Installed {
        val tmp = download(extension.apkUrl, extension.pkgName, ".apk")
        try {
            checkSigner(ExtensionSignatures.verifyApk(tmp), requiredSigner, extension)
            val manifest = ExtensionManifest.fromApk(tmp).also { checkManifest(it, extension) }
            val dir = packageDir(extension.pkgName)
            val apk = File(dir, "${manifest.versionCode}.apk")
            Files.move(tmp.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val jar = File(dir, "${manifest.versionCode}.jar")
            try {
                ApkConverter.convert(apk, jar)
            } catch (e: Exception) {
                apk.delete()
                throw e
            }
            return Installed(
                DesktopExtensionLoader.InstalledFiles(manifest.packageName, manifest.versionCode, jar, apk),
                Method.APK,
            )
        } finally {
            tmp.delete()
        }
    }

    fun uninstall(pkgName: String) {
        // Files of a loaded extension stay locked on Windows; the loader cleans them up later
        File(extensionsDir, pkgName).deleteRecursively()
    }

    private fun checkManifest(manifest: ExtensionManifest, extension: Extension.Available) {
        if (manifest.packageName != extension.pkgName) {
            throw ExtensionException(
                ExtensionException.Stage.MANIFEST,
                "Package ${manifest.packageName} does not match the store's ${extension.pkgName}",
            )
        }
        manifest.validate()
    }

    private fun packageDir(pkgName: String): File {
        require(pkgName.matches(PACKAGE_NAME)) { "Invalid package name $pkgName" }
        return File(extensionsDir, pkgName).apply { mkdirs() }
    }

    private suspend fun download(url: String, pkgName: String, suffix: String): File {
        require(pkgName.matches(PACKAGE_NAME)) { "Invalid package name $pkgName" }
        extensionsDir.mkdirs()
        val tmp = File.createTempFile("$pkgName-", suffix, extensionsDir)
        try {
            // Code is downloaded over https only (see requireSecureStoreUrl)
            client().newCall(GET(requireSecureStoreUrl(url))).awaitSuccess().use { response ->
                tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw ExtensionException(ExtensionException.Stage.DOWNLOAD, "Download of $url failed: ${e.message}", e)
        }
        return tmp
    }

    companion object {
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    }
}
