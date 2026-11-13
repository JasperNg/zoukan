package zoukan.desktop.security

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import zoukan.desktop.secrets.SecretStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The app lock, as Android's SecureActivityDelegate: with "Require unlock" on, the app asks for
 * its password at launch and when it comes back after "Lock when idle" minutes in the background,
 * on the days and in the time ranges chosen (all of them by default). Desktops have no app
 * biometrics prompt, so the lock uses a password kept, hashed, in the OS secret store; the
 * preferences are Android's, so a backup carries the choices.
 */
class AppLock(private val preferences: SecurityPreferences, private val secrets: SecretStore) {

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /**
     * Whether the lock is on. When the secret store can't be read (access denied, the keychain
     * locked), a lock that is turned on stays on rather than failing open: unlocking then shows
     * the store's error and can be tried again. Never throws, so the window's lifecycle hooks
     * can't crash the app.
     */
    val isEnabled: Boolean get() = preferences.useAuthenticator().get() && hasPasswordOrAssume()

    /** Reads the secret store; throws when it can't be read. */
    fun hasPassword(): Boolean = secrets.get(KEY) != null

    private fun hasPasswordOrAssume(): Boolean = try {
        hasPassword()
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Could not read the app lock password from ${secrets.name}" }
        true
    }

    /** At launch: always locked when the lock is on, as Android after the app was killed. */
    fun onStart() {
        _locked.value = isEnabled && canLockNow(LocalDateTime.now())
    }

    /** The window was minimized or hidden to the tray. */
    fun onBackground() {
        if (!isEnabled || _locked.value) return
        preferences.lastAppClosed().set(System.currentTimeMillis())
    }

    /** The window came back. */
    fun onForeground(now: Long = System.currentTimeMillis()) {
        if (!isEnabled || _locked.value) return
        val closed = preferences.lastAppClosed()
        if (!closed.isSet()) return
        val lock = canLockNow(LocalDateTime.now()) && when (val delay = preferences.lockAppAfter().get()) {
            -1 -> false
            0 -> true
            else -> closed.get() + delay * 60_000L <= now
        }
        closed.delete()
        if (lock) _locked.value = true
    }

    fun unlock(password: String): Boolean {
        val ok = verify(password)
        if (ok) _locked.value = false
        return ok
    }

    fun setPassword(password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        secrets.set(KEY, encode(salt, hash(password, salt)))
    }

    fun clearPassword() {
        secrets.delete(KEY)
        preferences.useAuthenticator().set(false)
        _locked.value = false
    }

    fun verify(password: String): Boolean {
        val stored = secrets.get(KEY) ?: return true
        val (salt, expected) = decode(stored) ?: return false
        return MessageDigest.isEqual(hash(password, salt), expected)
    }

    fun canLockNow(now: LocalDateTime): Boolean {
        val ranges = preferences.authenticatorTimeRanges().get().mapNotNull(::parseRange)
        val minutes = now.hour * 60 + now.minute
        val inRange = ranges.isEmpty() || ranges.any { minutes.toDouble() in it }
        val days = preferences.authenticatorDays().get()
        val today = when (now.dayOfWeek) {
            DayOfWeek.SUNDAY -> LOCK_SUNDAY
            DayOfWeek.MONDAY -> LOCK_MONDAY
            DayOfWeek.TUESDAY -> LOCK_TUESDAY
            DayOfWeek.WEDNESDAY -> LOCK_WEDNESDAY
            DayOfWeek.THURSDAY -> LOCK_THURSDAY
            DayOfWeek.FRIDAY -> LOCK_FRIDAY
            DayOfWeek.SATURDAY -> LOCK_SATURDAY
        }
        return inRange && (days == LOCK_ALL_DAYS || days and today == today)
    }

    private fun hash(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256))
            .encoded

    private fun encode(salt: ByteArray, hash: ByteArray) =
        Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash)

    private fun decode(value: String): Pair<ByteArray, ByteArray>? = runCatching {
        val (salt, hash) = value.split(":", limit = 2)
        Base64.getDecoder().decode(salt) to Base64.getDecoder().decode(hash)
    }.getOrNull()

    companion object {
        private const val KEY = "app_lock"
        private const val ITERATIONS = 210_000

        // Android's day bits (SecureActivityDelegate)
        const val LOCK_SUNDAY = 0x40
        const val LOCK_MONDAY = 0x20
        const val LOCK_TUESDAY = 0x10
        const val LOCK_WEDNESDAY = 0x8
        const val LOCK_THURSDAY = 0x4
        const val LOCK_FRIDAY = 0x2
        const val LOCK_SATURDAY = 0x1
        const val LOCK_ALL_DAYS = 0x7F

        /** Android's time range preference: "start,end" in minutes of the day. */
        fun parseRange(value: String): ClosedFloatingPointRange<Double>? {
            val (start, end) = value.split(",").takeIf { it.size == 2 } ?: return null
            return (start.toDoubleOrNull() ?: return null)..(end.toDoubleOrNull() ?: return null)
        }
    }
}
