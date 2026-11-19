package zoukan.extension

import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.data.extension.service.awaitSecureStoreResponse
import okhttp3.OkHttpClient
import xyz.nulldev.androidcompat.util.NoFollowDelete
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException

/**
 * Installs an extension from its store. Most stores publish a ready-made, JAR-signed JVM jar of
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

    /**
     * [keptAside] are the files of a same-version install that this install replaced, kept next to
     * the new ones until the caller decides: [dropPrevious] once the new files load, [restorePrevious]
     * when they do not. Empty for a fresh install or a new version.
     */
    data class Installed(
        val files: DesktopExtensionLoader.InstalledFiles,
        val method: Method,
        val keptAside: List<File> = emptyList(),
    )

    /** Puts the files this install replaced back, as they were. Only touches this package's folder. */
    fun restorePrevious(installed: Installed) {
        if (installed.keptAside.isEmpty()) return
        PreviousFiles.restore(checkedPackageDir(extensionsDir, installed.files.pkgName), installed.files.versionCode)
    }

    /** Deletes the files this install replaced: the new files work. Only touches this package's folder. */
    fun dropPrevious(installed: Installed) {
        if (installed.keptAside.isEmpty()) return
        PreviousFiles.drop(checkedPackageDir(extensionsDir, installed.files.pkgName), installed.files.versionCode)
    }

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
            return savingFiles {
                val dir = packageDir(extension.pkgName)
                val jar = File(dir, "${manifest.versionCode}.jar")
                // A same-version reinstall keeps the working files aside until the caller has loaded the new ones
                val kept = PreviousFiles.keepAside(dir, manifest.versionCode)
                try {
                    Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    PreviousFiles.restore(dir, manifest.versionCode)
                    throw e
                }
                // The hash sidecar earlier versions of Zoukan wrote for an APK-method install
                File(dir, "${manifest.versionCode}.sha256").delete()
                Installed(
                    DesktopExtensionLoader.InstalledFiles(manifest.packageName, manifest.versionCode, jar, null),
                    Method.JAR,
                    kept,
                )
            }
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
        var tmpApk: File? = null
        try {
            // The APK is read once (the download is size-capped): the bytes that are verified are
            // the bytes that are parsed, converted and stored, whatever happens to the file after
            val apkBytes = tmp.readBytes()
            checkSigner(ExtensionSignatures.verifyApk(apkBytes), requiredSigner, extension)
            val manifest = ExtensionManifest.fromApk(apkBytes).also { checkManifest(it, extension, installedVersionCode) }
            // The conversion's own failures are ExtensionExceptions of its stage, which pass through
            return savingFiles {
                val dir = packageDir(extension.pkgName)
                tmpJar = File.createTempFile("${manifest.versionCode}-converting-", ".jar.part", dir)
                ApkConverter.convert(apkBytes, tmpJar)
                val sha256 = ApkConverter.sha256Of(tmpJar)
                tmpApk = File.createTempFile("${manifest.versionCode}-converting-", ".apk.part", dir)
                tmpApk.writeBytes(apkBytes)
                val apk = File(dir, "${manifest.versionCode}.apk")
                val jar = File(dir, "${manifest.versionCode}.jar")
                // A same-version reinstall keeps the working files aside until the caller has loaded the new ones
                val kept = PreviousFiles.keepAside(dir, manifest.versionCode)
                try {
                    Files.move(tmpApk.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Files.move(tmpJar.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    PreviousFiles.restore(dir, manifest.versionCode)
                    throw e
                }
                File(dir, "${manifest.versionCode}.sha256").delete()
                Installed(
                    DesktopExtensionLoader.InstalledFiles(manifest.packageName, manifest.versionCode, jar, apk, sha256),
                    Method.APK,
                    kept,
                )
            }
        } finally {
            tmp.delete()
            tmpJar?.delete()
            tmpApk?.delete()
        }
    }

    fun uninstall(pkgName: String) {
        // Loaded extensions run from memory (ExtensionClassLoader), so no file is held open. Never
        // through a link: the canonical path may leave a Windows junction unresolved
        NoFollowDelete.deleteTree(checkedPackageDir(extensionsDir, pkgName).toPath())
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
        val dir = File(extensionsDir, pkgName)
        // A file in the way, or a folder that cannot be written: reported by savingFiles
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Could not create the folder of $pkgName")
        return dir
    }

    /**
     * Runs [block], which writes an extension's files into its package folder, and reports a folder
     * that cannot be written (read-only, a file in the way, a full disk) as an extension error the
     * caller shows, as [download] does for the extensions folder. Only the message's file system
     * error is kept, not its paths.
     */
    private inline fun <T> savingFiles(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw ExtensionException(
            ExtensionException.Stage.DOWNLOAD,
            "Could not save the extension in the extensions folder (${e::class.simpleName})",
            e,
        )
    }

    private suspend fun download(url: String, pkgName: String, suffix: String): File {
        require(pkgName.matches(PACKAGE_NAME)) { "Invalid package name $pkgName" }
        val tmp = try {
            extensionsDir.mkdirs()
            File.createTempFile("$pkgName-", suffix, extensionsDir)
        } catch (e: IOException) {
            // A missing, read-only or blocked extensions folder: an extension error the caller reports
            throw ExtensionException(
                ExtensionException.Stage.DOWNLOAD,
                "Could not save the download in the extensions folder: ${e.message}",
                e,
            )
        }
        try {
            // Code is downloaded over https only, also after redirects (see awaitSecureStoreResponse)
            client().awaitSecureStoreResponse(url).use { response ->
                val limit = ExtensionLimits.MAX_DOWNLOAD_BYTES
                val tooLarge = ExtensionException(
                    ExtensionException.Stage.DOWNLOAD,
                    "Download of $url is too large (over ${ExtensionLimits.megabytes(limit)} MB)",
                )
                if (response.body.contentLength() > limit) throw tooLarge
                tmp.outputStream().use { out ->
                    response.body.byteStream().copyAtMost(out, limit, ExtensionException.Stage.DOWNLOAD, url)
                }
            }
        } catch (e: CancellationException) {
            // Cancelled, not failed: the caller's cancellation must go on
            tmp.delete()
            throw e
        } catch (e: ExtensionException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw ExtensionException(ExtensionException.Stage.DOWNLOAD, "Download of $url failed: ${e.message}", e)
        }
        return tmp
    }
}
