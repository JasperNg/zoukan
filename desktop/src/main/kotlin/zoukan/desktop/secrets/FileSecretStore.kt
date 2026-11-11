package zoukan.desktop.secrets

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Secrets in files readable only by the user. Only for Linux, where Zoukan runs for development
 * and has no OS secret store; Windows and macOS use their own stores.
 */
class FileSecretStore(private val dir: File) : SecretStore {
    override val name = "files in $dir (development only)"

    override fun get(key: String): String? = file(key).takeIf { it.isFile }?.readText()

    override fun set(key: String, value: String) {
        dir.mkdirs()
        restrict(dir, "rwx------")
        val file = file(key)
        file.writeText(value)
        restrict(file, "rw-------")
    }

    override fun delete(key: String) {
        file(key).delete()
    }

    private fun file(key: String): File {
        require(key.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid secret name $key" }
        return File(dir, key)
    }

    private fun restrict(file: File, permissions: String) {
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(permissions)) }
    }
}
