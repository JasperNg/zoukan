package zoukan.desktop.ui.settings

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import xyz.nulldev.androidcompat.util.rethrowIfVmFailure
import zoukan.desktop.compat.localized
import zoukan.desktop.security.AppLock
import zoukan.desktop.security.Incognito
import zoukan.desktop.ui.security.secretStoreError
import zoukan.i18n.desktop.DMR

/**
 * Non-UI state/business controller for Security settings: app lock setup/verification, password
 * validation and removal, schedule parsing/formatting, and per-extension incognito toggles.
 */
class SecuritySettingsController(
    private val preferences: SecurityPreferences,
    private val lock: AppLock,
    private val incognito: Incognito,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    sealed interface ToggleLockResult {
        data object Disabled : ToggleLockResult
        data object Enabled : ToggleLockResult
        data object NeedsPasswordSetup : ToggleLockResult
        data class SecretStoreFailed(val message: String) : ToggleLockResult
    }

    sealed interface PasswordActionResult {
        data object Saved : PasswordActionResult
        data object Removed : PasswordActionResult
        data class ValidationError(val message: String) : PasswordActionResult
        data object WrongCurrentPassword : PasswordActionResult
        data class SecretStoreError(val message: String) : PasswordActionResult
    }

    sealed interface TimeRangeResult {
        data class Added(val range: String) : TimeRangeResult
        data class Invalid(val message: String) : TimeRangeResult
    }

    suspend fun toggleLock(enabled: Boolean): ToggleLockResult {
        if (!enabled) {
            preferences.useAuthenticator().set(false)
            return ToggleLockResult.Disabled
        }
        return secretStoreCall { lock.hasPassword() }.fold(
            onSuccess = { hasPassword ->
                if (hasPassword) {
                    preferences.useAuthenticator().set(true)
                    ToggleLockResult.Enabled
                } else {
                    ToggleLockResult.NeedsPasswordSetup
                }
            },
            onFailure = { ToggleLockResult.SecretStoreFailed(secretStoreError(it)) },
        )
    }

    suspend fun hasPassword(): Result<Boolean> = secretStoreCall { lock.hasPassword() }

    fun validateNewPassword(password: String, repeat: String): String? = when {
        password.length < MIN_PASSWORD_LENGTH -> localized(DMR.strings.use_at_least_4_characters)
        password != repeat -> localized(DMR.strings.the_passwords_differ)
        else -> null
    }

    suspend fun savePassword(
        hasCurrentPassword: Boolean,
        currentPassword: String,
        newPassword: String,
        repeatPassword: String,
    ): PasswordActionResult {
        validateNewPassword(newPassword, repeatPassword)?.let {
            return PasswordActionResult.ValidationError(it)
        }
        return secretStoreCall {
            if (hasCurrentPassword && !lock.verify(currentPassword)) return@secretStoreCall false
            lock.setPassword(newPassword)
            preferences.useAuthenticator().set(true)
            true
        }.fold(
            onSuccess = { saved ->
                if (saved) PasswordActionResult.Saved else PasswordActionResult.WrongCurrentPassword
            },
            onFailure = { PasswordActionResult.SecretStoreError(secretStoreError(it)) },
        )
    }

    suspend fun removePassword(currentPassword: String): PasswordActionResult =
        secretStoreCall {
            lock.verify(currentPassword).also { verified -> if (verified) lock.clearPassword() }
        }.fold(
            onSuccess = { removed ->
                if (removed) PasswordActionResult.Removed else PasswordActionResult.WrongCurrentPassword
            },
            onFailure = { PasswordActionResult.SecretStoreError(secretStoreError(it)) },
        )

    fun saveDays(daysMask: Int) {
        preferences.authenticatorDays().set(daysMask)
    }

    fun saveTimeRanges(ranges: Collection<String>) {
        preferences.authenticatorTimeRanges().set(ranges.toSet())
    }

    fun addTimeRange(startText: String, endText: String): TimeRangeResult {
        val from = parseMinutes(startText)
        val to = parseMinutes(endText)
        return if (from == null || to == null || to <= from) {
            TimeRangeResult.Invalid(localized(DMR.strings.use_hh_mm_with_the_end))
        } else {
            TimeRangeResult.Added("$from,$to")
        }
    }

    fun setExtensionIncognito(pkgName: String, enabled: Boolean) {
        incognito.setExtension(pkgName, enabled)
    }

    private suspend fun <T> secretStoreCall(block: () -> T): Result<T> =
        try {
            // NonCancellable: closing the dialog midway must not leave a password half changed
            Result.success(withContext(ioDispatcher + NonCancellable) { block() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.rethrowIfVmFailure()
            AppLock.logcat(LogPriority.WARN, e) { "App lock: the secret store failed" }
            Result.failure(e)
        }

    companion object {
        private const val MIN_PASSWORD_LENGTH = 4

        fun parseMinutes(text: String): Int? {
            val (h, m) = text.trim().split(":").takeIf { it.size == 2 } ?: return null
            val hour = h.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
            val minute = m.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            return hour * 60 + minute
        }

        fun formatHhmm(minutes: Double): String = "%02d:%02d".format(minutes.toInt() / 60, minutes.toInt() % 60)

        fun daysSummary(daysMask: Int, dayLabels: List<Pair<String, Int>>): String =
            if (daysMask == AppLock.LOCK_ALL_DAYS) {
                localized(DMR.strings.every_day)
            } else {
                dayLabels.filter { daysMask and it.second != 0 }.joinToString { it.first }.ifEmpty { localized(DMR.strings.no_day) }
            }

        fun timeRangesSummary(ranges: Set<String>): String =
            ranges.mapNotNull(AppLock::parseRange)
                .joinToString { localized(DMR.strings.to, formatHhmm(it.start), formatHhmm(it.endInclusive)) }
                .ifEmpty { localized(DMR.strings.all_day) }
    }
}
