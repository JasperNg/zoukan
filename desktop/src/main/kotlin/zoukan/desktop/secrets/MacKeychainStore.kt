package zoukan.desktop.secrets

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Generic passwords in the user's login keychain, with service `Zoukan` and the key as the account.
 *
 * Uses the SecKeychain generic password calls, which need no CoreFoundation dictionaries.
 */
class MacKeychainStore : SecretStore {
    override val name = "macOS Keychain"

    private val service = SecretStore.SERVICE.encodeToByteArray()

    override fun get(key: String): String? {
        val account = key.encodeToByteArray()
        val length = IntByReference()
        val data = PointerByReference()
        val status = Security.INSTANCE.SecKeychainFindGenericPassword(
            null,
            service.size,
            service,
            account.size,
            account,
            length,
            data,
            null,
        )
        if (status == ERR_SEC_ITEM_NOT_FOUND) return null
        check(status == 0) { "SecKeychainFindGenericPassword failed with status $status" }
        try {
            return data.value.getByteArray(0, length.value).decodeToString()
        } finally {
            Security.INSTANCE.SecKeychainItemFreeContent(null, data.value)
        }
    }

    override fun set(key: String, value: String) {
        val account = key.encodeToByteArray()
        val password = value.encodeToByteArray()
        val item = PointerByReference()
        val found = Security.INSTANCE.SecKeychainFindGenericPassword(
            null,
            service.size,
            service,
            account.size,
            account,
            null,
            null,
            item,
        )
        if (found == 0) {
            try {
                val status = Security.INSTANCE.SecKeychainItemModifyAttributesAndData(
                    item.value,
                    null,
                    password.size,
                    password,
                )
                check(status == 0) { "SecKeychainItemModifyAttributesAndData failed with status $status" }
            } finally {
                CoreFoundation.INSTANCE.CFRelease(item.value)
            }
        } else {
            check(found == ERR_SEC_ITEM_NOT_FOUND) { "SecKeychainFindGenericPassword failed with status $found" }
            val status = Security.INSTANCE.SecKeychainAddGenericPassword(
                null,
                service.size,
                service,
                account.size,
                account,
                password.size,
                password,
                null,
            )
            check(status == 0) { "SecKeychainAddGenericPassword failed with status $status" }
        }
    }

    override fun delete(key: String) {
        val account = key.encodeToByteArray()
        val item = PointerByReference()
        val found = Security.INSTANCE.SecKeychainFindGenericPassword(
            null,
            service.size,
            service,
            account.size,
            account,
            null,
            null,
            item,
        )
        if (found == ERR_SEC_ITEM_NOT_FOUND) return
        check(found == 0) { "SecKeychainFindGenericPassword failed with status $found" }
        try {
            val status = Security.INSTANCE.SecKeychainItemDelete(item.value)
            check(status == 0) { "SecKeychainItemDelete failed with status $status" }
        } finally {
            CoreFoundation.INSTANCE.CFRelease(item.value)
        }
    }

    @Suppress("FunctionName")
    private interface Security : Library {
        fun SecKeychainFindGenericPassword(
            keychainOrArray: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: IntByReference?,
            passwordData: PointerByReference?,
            itemRef: PointerByReference?,
        ): Int

        fun SecKeychainAddGenericPassword(
            keychain: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: Int,
            passwordData: ByteArray,
            itemRef: PointerByReference?,
        ): Int

        fun SecKeychainItemModifyAttributesAndData(
            itemRef: Pointer,
            attrList: Pointer?,
            length: Int,
            data: ByteArray,
        ): Int

        fun SecKeychainItemFreeContent(attrList: Pointer?, data: Pointer?): Int

        fun SecKeychainItemDelete(itemRef: Pointer): Int

        companion object {
            val INSTANCE: Security = Native.load("Security", Security::class.java)
        }
    }

    @Suppress("FunctionName")
    private interface CoreFoundation : Library {
        fun CFRelease(cf: Pointer)

        companion object {
            val INSTANCE: CoreFoundation = Native.load("CoreFoundation", CoreFoundation::class.java)
        }
    }

    private companion object {
        const val ERR_SEC_ITEM_NOT_FOUND = -25300
    }
}
