package zoukan.extension

import java.io.File

/** What a package name may look like: dotted words, so it can never name a path outside its folder. */
internal val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

/**
 * The folder of [pkgName] directly inside [extensionsDir]. Every delete of a package's files goes
 * through here, so nothing outside the extensions folder (backups, the database, downloads) can be
 * reached through a package name.
 *
 * @throws IllegalArgumentException when [pkgName] is not a valid package name, or its folder does
 * not resolve to a direct child of [extensionsDir] (for example through a link)
 */
internal fun checkedPackageDir(extensionsDir: File, pkgName: String): File {
    require(pkgName.matches(PACKAGE_NAME)) { "Invalid package name $pkgName" }
    val root = extensionsDir.canonicalFile
    val dir = File(root, pkgName).canonicalFile
    require(dir.parentFile == root) { "$pkgName is not inside the extensions folder" }
    return dir
}
