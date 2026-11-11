package zoukan.desktop.secrets

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinBase.FILETIME
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * Generic credentials in Windows Credential Manager, named `Zoukan/<key>`, readable only by the
 * signed-in Windows user (CRED_PERSIST_LOCAL_MACHINE keeps them on this computer, not roaming).
 */
class WindowsCredentialStore : SecretStore {
    override val name = "Windows Credential Manager"

    override fun get(key: String): String? {
        val ref = PointerByReference()
        if (!Advapi32Cred.INSTANCE.CredReadW(WString(target(key)), CRED_TYPE_GENERIC, 0, ref)) {
            val error = Native.getLastError()
            if (error == ERROR_NOT_FOUND) return null
            throw IllegalStateException("CredReadW failed with error $error")
        }
        try {
            val credential = Credential(ref.value)
            if (credential.CredentialBlobSize == 0 || credential.CredentialBlob == null) return ""
            return credential.CredentialBlob!!.getByteArray(0, credential.CredentialBlobSize).decodeToString()
        } finally {
            Advapi32Cred.INSTANCE.CredFree(ref.value)
        }
    }

    override fun set(key: String, value: String) {
        val bytes = value.encodeToByteArray()
        val blob = Memory(bytes.size.coerceAtLeast(1).toLong()).apply { write(0, bytes, 0, bytes.size) }
        val credential = Credential().apply {
            Type = CRED_TYPE_GENERIC
            TargetName = WString(target(key))
            UserName = WString(System.getProperty("user.name"))
            CredentialBlobSize = bytes.size
            CredentialBlob = blob
            Persist = CRED_PERSIST_LOCAL_MACHINE
        }
        try {
            if (!Advapi32Cred.INSTANCE.CredWriteW(credential, 0)) {
                throw IllegalStateException("CredWriteW failed with error ${Native.getLastError()}")
            }
        } finally {
            blob.clear()
        }
    }

    override fun delete(key: String) {
        if (!Advapi32Cred.INSTANCE.CredDeleteW(WString(target(key)), CRED_TYPE_GENERIC, 0)) {
            val error = Native.getLastError()
            if (error != ERROR_NOT_FOUND) throw IllegalStateException("CredDeleteW failed with error $error")
        }
    }

    private fun target(key: String) = "${SecretStore.SERVICE}/$key"

    @Suppress("PropertyName", "unused")
    @Structure.FieldOrder(
        "Flags", "Type", "TargetName", "Comment", "LastWritten", "CredentialBlobSize", "CredentialBlob",
        "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName",
    )
    class Credential : Structure {
        @JvmField var Flags: Int = 0

        @JvmField var Type: Int = 0

        @JvmField var TargetName: WString? = null

        @JvmField var Comment: WString? = null

        @JvmField var LastWritten: FILETIME = FILETIME()

        @JvmField var CredentialBlobSize: Int = 0

        @JvmField var CredentialBlob: Pointer? = null

        @JvmField var Persist: Int = 0

        @JvmField var AttributeCount: Int = 0

        @JvmField var Attributes: Pointer? = null

        @JvmField var TargetAlias: WString? = null

        @JvmField var UserName: WString? = null

        constructor() : super()

        constructor(pointer: Pointer) : super(pointer) {
            read()
        }
    }

    @Suppress("FunctionName")
    private interface Advapi32Cred : StdCallLibrary {
        fun CredReadW(target: WString, type: Int, flags: Int, credential: PointerByReference): Boolean

        fun CredWriteW(credential: Credential, flags: Int): Boolean

        fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean

        fun CredFree(buffer: Pointer)

        companion object {
            val INSTANCE: Advapi32Cred =
                Native.load("Advapi32", Advapi32Cred::class.java, W32APIOptions.UNICODE_OPTIONS)
        }
    }

    private companion object {
        const val CRED_TYPE_GENERIC = 1
        const val CRED_PERSIST_LOCAL_MACHINE = 2
        const val ERROR_NOT_FOUND = 1168
    }
}
