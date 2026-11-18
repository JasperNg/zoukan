package zoukan.desktop.testing.fakes

import zoukan.desktop.secrets.SecretStore
import java.util.concurrent.atomic.AtomicInteger

/**
 * A secret store that fails on demand, the way the OS stores fail: their errors are
 * IllegalStateExceptions that carry the native status (see MacKeychainStore and
 * WindowsCredentialStore). Reads, writes and deletes fail separately, and each can first succeed a
 * given number of times, for a failure midway (the key is read, a later write fails).
 *
 * A corrupt or garbled value is a value put in [backing] directly.
 */
class FailingSecretStore(val backing: MemorySecretStore = MemorySecretStore()) : SecretStore {

    /** The ways an OS store fails, with the error each one reports. */
    enum class Failure(private val macStatus: Int?, private val windowsError: Int?) {
        /** macOS: the login keychain is locked and no prompt may be shown (errSecInteractionNotAllowed). */
        LOCKED(macStatus = -25308, windowsError = null),

        /** macOS: the user chose Deny in the Keychain prompt (errSecUserCanceled); Windows: access denied. */
        DENIED(macStatus = -128, windowsError = 5),

        /** Windows: no Credential Manager in this logon session, as over SSH (ERROR_NO_SUCH_LOGON_SESSION). */
        UNAVAILABLE(macStatus = null, windowsError = 1312),
        ;

        fun exception(operation: Operation): IllegalStateException = when {
            macStatus != null -> IllegalStateException("${operation.macCall} failed with status $macStatus")
            else -> IllegalStateException("${operation.windowsCall} failed with error $windowsError")
        }
    }

    enum class Operation(val macCall: String, val windowsCall: String) {
        READ("SecKeychainFindGenericPassword", "CredReadW"),
        WRITE("SecKeychainAddGenericPassword", "CredWriteW"),
        DELETE("SecKeychainItemDelete", "CredDeleteW"),
    }

    private class Plan(val failure: Failure, val succeedFirst: Int)

    override val name = "failing test store"

    private val plans = mutableMapOf<Operation, Plan>()
    private val counts = Operation.entries.associateWith { AtomicInteger() }

    /** Every key a write was attempted for, in order, whether or not it failed. */
    val attemptedWrites: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** Makes [operation] fail with [failure] after it has succeeded [succeedFirst] times from now. */
    @Synchronized
    fun failOn(operation: Operation, failure: Failure, succeedFirst: Int = 0): FailingSecretStore = apply {
        counts.getValue(operation).set(0)
        plans[operation] = Plan(failure, succeedFirst)
    }

    /** Makes every operation fail with [failure]. */
    fun failAll(failure: Failure): FailingSecretStore = apply { Operation.entries.forEach { failOn(it, failure) } }

    @Synchronized
    fun heal(): FailingSecretStore = apply { plans.clear() }

    @Synchronized
    private fun check(operation: Operation) {
        val plan = plans[operation] ?: return
        if (counts.getValue(operation).getAndIncrement() >= plan.succeedFirst) throw plan.failure.exception(operation)
    }

    override fun get(key: String): String? {
        check(Operation.READ)
        return backing.get(key)
    }

    override fun set(key: String, value: String) {
        attemptedWrites += key
        check(Operation.WRITE)
        backing.set(key, value)
    }

    override fun delete(key: String) {
        check(Operation.DELETE)
        backing.delete(key)
    }
}
