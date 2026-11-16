package zoukan.extension

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The files of an installed version that a reinstall of the same version kept aside (`N.jar.previous`,
 * `N.apk.previous`, in the package's own folder) until the new files are known to load. The loader
 * only reads `N.jar`, so a kept-aside file is never loaded and the temp-file cleanup never removes it.
 *
 * All moves are renames inside one folder. The APK is moved aside before the jar, so a kept-aside
 * jar without a kept-aside APK means the previous version had no APK.
 */
internal object PreviousFiles {
    private const val SUFFIX = ".previous"

    /** Moves the installed jar and APK of [versionCode] in [dir] aside and returns the moved files. */
    fun keepAside(dir: File, versionCode: Long): List<File> {
        val kept = mutableListOf<File>()
        try {
            for (name in listOf("$versionCode.apk", "$versionCode.jar")) {
                val file = File(dir, name)
                if (!file.isFile) continue
                val aside = File(dir, name + SUFFIX)
                Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING)
                kept += aside
            }
        } catch (e: Exception) {
            restore(dir, versionCode)
            throw e
        }
        return kept
    }

    /**
     * Puts the kept-aside files of [versionCode] back over the new ones. Does nothing when nothing
     * was kept aside. A new APK the previous version had no counterpart of is the installer's own
     * file and is removed, so the restored jar is not read as an APK-method install.
     */
    fun restore(dir: File, versionCode: Long) {
        val jarAside = File(dir, "$versionCode.jar$SUFFIX")
        val apkAside = File(dir, "$versionCode.apk$SUFFIX")
        if (!jarAside.isFile && !apkAside.isFile) return
        if (apkAside.isFile) {
            Files.move(apkAside.toPath(), File(dir, "$versionCode.apk").toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            File(dir, "$versionCode.apk").delete()
        }
        if (jarAside.isFile) {
            Files.move(jarAside.toPath(), File(dir, "$versionCode.jar").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** The reinstall was confirmed: the kept-aside files of [versionCode] are not needed any more. */
    fun drop(dir: File, versionCode: Long) {
        File(dir, "$versionCode.jar$SUFFIX").delete()
        File(dir, "$versionCode.apk$SUFFIX").delete()
    }

    /** Puts back every kept-aside file in [dir]: a reinstall that never confirmed (a crash). */
    fun restoreAll(dir: File) {
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(SUFFIX) }
            .mapNotNull { it.name.substringBefore('.').toLongOrNull() }
            .distinct()
            .forEach { restore(dir, it) }
    }
}
