package zoukan.desktop.download

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import zoukan.desktop.secrets.SecretStore
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.security.SecureRandom

/**
 * The password for encrypted CBZ downloads. The Android app keeps it in a preference, encrypted
 * with an Android Keystore key (CbzCrypto); Zoukan keeps it in the OS secret store. The user types
 * the same password on both, so archives move between devices.
 */
class CbzPassword(
    private val secrets: SecretStore,
    private val preferences: SecurityPreferences,
) {
    fun get(): CharArray? = runCatching { secrets.get(KEY) }.getOrNull()?.takeIf { it.isNotEmpty() }?.toCharArray()

    fun isSet(): Boolean = get() != null

    fun set(password: String) {
        if (password.isEmpty()) secrets.delete(KEY) else secrets.set(KEY, password)
    }

    /**
     * The password for a new download, or null when downloads are not encrypted: the setting is
     * off, or no password is set. When the setting is on and the secret store fails (a locked
     * Keychain, a cancelled prompt, a Credential Manager error), it throws
     * [CbzPasswordUnavailableException], so the download fails instead of being saved unencrypted.
     */
    private fun downloadPassword(): CharArray? {
        if (!preferences.passwordProtectDownloads().get()) return null
        val stored = try {
            secrets.get(KEY)
        } catch (e: Exception) {
            throw CbzPasswordUnavailableException(e)
        }
        return stored?.takeIf { it.isNotEmpty() }?.toCharArray()
    }

    /**
     * Whether new downloads are encrypted: the setting is on and a password is set. Throws
     * [CbzPasswordUnavailableException] when the setting is on and the secret store fails.
     */
    fun encryptDownloads(): Boolean = downloadPassword() != null

    /** The encryption for a new download; see [downloadPassword] for when it throws. */
    fun encryption(): CbzEncryption? {
        val password = downloadPassword() ?: return null
        return CbzEncryption(password, preferences.encryptionType().get())
    }

    /**
     * The random padding SY adds to ComicInfo.xml in encrypted archives, so its size gives nothing
     * away. Takes the download's [encryption] (from [encryption]), so the secret store is read once.
     */
    fun comicInfoPadding(encryption: CbzEncryption?): String? {
        if (encryption == null) return null
        val pool = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val random = SecureRandom()
        return List(random.nextInt(100) + 42) { pool[random.nextInt(pool.size)] }.joinToString("")
    }

    companion object {
        const val KEY = "cbz-password"
    }
}

/** The secret store could not be read while "Password protect downloads" is on. */
class CbzPasswordUnavailableException(cause: Throwable) :
    IllegalStateException(
        "Could not read the download password from the system's secret store, so the chapter was not saved " +
            "(password protect downloads is on). Unlock the secret store and retry. (${cause.message ?: cause::class.simpleName})",
        cause,
    )

class CbzEncryption(val password: CharArray, val type: SecurityPreferences.EncryptionType)

/** Writes a CBZ: stored, not compressed, as the Android app writes them; optionally encrypted. */
class CbzWriter(file: File, private val encryption: CbzEncryption? = null) : Closeable {
    private val out = ZipOutputStream(file.outputStream().buffered(), encryption?.password)

    fun write(name: String, bytes: ByteArray) {
        val params = ZipParameters().apply {
            fileNameInZip = name
            compressionMethod = CompressionMethod.STORE
            entrySize = bytes.size.toLong()
            lastModifiedFileTime = System.currentTimeMillis()
            if (encryption != null) {
                isEncryptFiles = true
                when (encryption.type) {
                    SecurityPreferences.EncryptionType.AES_256 -> {
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                    SecurityPreferences.EncryptionType.AES_128 -> {
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_128
                    }
                    SecurityPreferences.EncryptionType.ZIP_STANDARD -> encryptionMethod = EncryptionMethod.ZIP_STANDARD
                }
            }
        }
        out.putNextEntry(params)
        out.write(bytes)
        out.closeEntry()
    }

    override fun close() = out.close()
}

/**
 * Reads a CBZ or ZIP, encrypted or not. The password for encrypted ones comes from [password];
 * opening an entry of an encrypted archive without one fails with a message saying so.
 */
class CbzReader(val file: File, password: () -> CharArray? = { null }) : Closeable {
    private val zip = ZipFile(file)

    val encrypted: Boolean = runCatching { zip.isEncrypted }.getOrDefault(false)

    init {
        if (encrypted) {
            val pw = password() ?: run {
                zip.close()
                throw IllegalStateException("${file.name} is password protected. Set the download password in Settings.")
            }
            zip.setPassword(pw)
        }
    }

    val entries: List<FileHeader> = zip.fileHeaders.filterNot { it.isDirectory }

    /** The image entries in reading order. */
    val images: List<FileHeader>
        get() = entries.filter { ImageType.isImageName(it.fileName) && !it.fileName.substringAfterLast('/').startsWith('.') }
            .sortedWith(compareBy(FileNames.naturalOrder) { it.fileName })

    fun find(name: String): FileHeader? = entries.firstOrNull { it.fileName.equals(name, ignoreCase = true) }

    fun open(header: FileHeader): InputStream = zip.getInputStream(header)

    fun read(header: FileHeader): ByteArray = open(header).use { it.readBytes() }

    override fun close() = zip.close()
}
