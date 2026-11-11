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

    /** Whether new downloads are encrypted: the setting is on and a password is set. */
    fun encryptDownloads(): Boolean = preferences.passwordProtectDownloads().get() && isSet()

    fun encryption(): CbzEncryption? {
        if (!encryptDownloads()) return null
        return CbzEncryption(get()!!, preferences.encryptionType().get())
    }

    /** The random padding SY adds to ComicInfo.xml in encrypted archives, so its size gives nothing away. */
    fun comicInfoPadding(): String? {
        if (!encryptDownloads()) return null
        val pool = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val random = SecureRandom()
        return List(random.nextInt(100) + 42) { pool[random.nextInt(pool.size)] }.joinToString("")
    }

    companion object {
        const val KEY = "cbz-password"
    }
}

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
