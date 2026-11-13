package zoukan.extension

import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.data.extension.service.awaitSecureStoreResponse
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException

/**
 * Installs an extension from its store. Keiyoushi publishes a ready-made, JAR-signed JVM jar of
 * each extension (the store's jarUrl), which is installed as it is. For a store that publishes only
 * APKs, the APK is converted with dex2jar, and the converted jar's SHA-256 is handed to the loader
 * (InstalledFiles.convertedSha256), which records it outside the extensions folder: unlike the
 * signed jar or APK, the conversion's output is unsigned user-writable data, so the loader only
 * runs it while it still matches that hash.
 *
 * Store files are downloaded over https only, and a redirect to plain http is refused
 * (awaitSecureStoreResponse).
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
     * @param installedVersionCode the version installed now, when this is an update or a
     * reinstall: an older build is refused, as Android refuses a downgrade.
     */
    suspend fun install(
        extension: Extension.Available,
        forceApk: Boolean = false,
        requiredSigner: String? = null,
        installedVersionCode: Long? = null,
    ): Installed =
        withContext(Dispatchers.IO) {
            val jarUrl = extension.jarUrl
            if (jarUrl != null && !forceApk) {
                installJar(extension, jarUrl, requiredSigner, installedVersionCode)
            } else {
                installApk(extension, requiredSigner, installedVersionCode)
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

    private suspend fun installJar(
        extension: Extension.Available,
        url: String,
        requiredSigner: String?,
        installedVersionCode: Long?,
    ): Installed {
        val tmp = download(url, extension.pkgName, ".jar")
        try {
            val verified = ExtensionSignatures.verifyJar(tmp)
            checkSigner(verified.signers, requiredSigner, extension)
            val manifest = ExtensionManifest.fromContents(verified.contents, tmp.name)
                .also { checkManifest(it, extension, installedVersionCode) }
            val dir = packageDir(extension.pkgName)
            val jar = File(dir, "${manifest.versionCode}.jar")
            Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            File(dir, "${manifest.versionCode}.apk").delete()
            // The hash sidecar earlier versions of Zoukan wrote for an APK-method install
            File(dir, "${manifest.versionCode}.sha256").delete()
            return Installed(
                DesktopExtensionLoader.InstalledFiles(manifest.packageName, manifest.versionCode, jar, null),
                Method.JAR,
            )
        } finally {
            tmp.delete()
        }
    }

    /**
     * Converts into temporary files and moves the APK and the jar into place only once the
     * conversion worked, so a failed conversion leaves an installed copy of the same version (and
     * its verified APK) as it was.
     */
    private suspend fun installApk(
        extension: Extension.Available,
        requiredSigner: String?,
        installedVersionCode: Long?,
    ): Installed {
        val tmp = download(extension.apkUrl, extension.pkgName, ".apk")
        var tmpJar: File? = null
        try {
            checkSigner(ExtensionSignatures.verifyApk(tmp), requiredSigner, extension)
            val manifest = ExtensionManifest.fromApk(tmp).also { checkManifest(it, extension, installedVersionCode) }
            val dir = packageDir(extension.pkgName)
            tmpJar = File.createTempFile("${manifest.versionCode}-converting-", ".jar.part", dir)
            ApkConverter.convert(tmp, tmpJar)
            val sha256 = ApkConverter.sha256Of(tmpJar)
            val apk = File(dir, "${manifest.versionCode}.apk")
            val jar = File(dir, "${manifest.versionCode}.jar")
            // The APK first: if the jar's move then fails, the old jar no longer matches its hash
            // and the loader converts this APK again
            Files.move(tmp.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmpJar.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            File(dir, "${manifest.versionCode}.sha256").delete()
            return Installed(
                DesktopExtensionLoader.InstalledFiles(manifest.packageName, manifest.versionCode, jar, apk, sha256),
                Method.APK,
            )
        } finally {
            tmp.delete()
            tmpJar?.delete()
        }
    }

    fun uninstall(pkgName: String) {
        // Loaded extensions run from memory (ExtensionClassLoader), so no file is held open
        File(extensionsDir, pkgName).deleteRecursively()
    }

    private fun checkManifest(manifest: ExtensionManifest, extension: Extension.Available, installedVersionCode: Long?) {
        if (manifest.packageName != extension.pkgName) {
            throw ExtensionException(
                ExtensionException.Stage.MANIFEST,
                "Package ${manifest.packageName} does not match the store's ${extension.pkgName}",
            )
        }
        if (manifest.versionCode != extension.versionCode) {
            throw ExtensionException(
                ExtensionException.Stage.MANIFEST,
                "${extension.name}: the file is version ${manifest.versionCode}, but the store lists " +
                    "version ${extension.versionCode}",
            )
        }
        if (installedVersionCode != null && manifest.versionCode < installedVersionCode) {
            throw ExtensionException(
                ExtensionException.Stage.MANIFEST,
                "${extension.name}: version ${manifest.versionCode} is older than the installed version " +
                    "$installedVersionCode, so it was not installed",
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
            // Code is downloaded over https only, also after redirects (see awaitSecureStoreResponse)
            client().awaitSecureStoreResponse(url).use { response ->
                tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
        } catch (e: CancellationException) {
            // Cancelled, not failed: the caller's cancellation must go on
            tmp.delete()
            throw e
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
