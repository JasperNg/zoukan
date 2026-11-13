package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import exh.source.EH_PACKAGE
import exh.source.LOCAL_SOURCE_PACKAGE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.security.AppLock
import zoukan.desktop.security.Incognito
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.security.secretStoreError
import zoukan.extension.DesktopExtensionManager
import zoukan.i18n.desktop.DMR

/** Settings > Security and privacy: the app lock and incognito mode, as on Android. */
fun LazyListScope.securitySettings() {
    settingsGroup(localized(MR.strings.pref_category_security))
    item(key = "security") { SecuritySettings() }
}

private val DAYS = listOf(
    localized(SYMR.strings.monday) to AppLock.LOCK_MONDAY,
    localized(SYMR.strings.tuesday) to AppLock.LOCK_TUESDAY,
    localized(SYMR.strings.wednesday) to AppLock.LOCK_WEDNESDAY,
    localized(SYMR.strings.thursday) to AppLock.LOCK_THURSDAY,
    localized(SYMR.strings.friday) to AppLock.LOCK_FRIDAY,
    localized(SYMR.strings.saturday) to AppLock.LOCK_SATURDAY,
    localized(SYMR.strings.sunday) to AppLock.LOCK_SUNDAY,
)

@Composable
private fun SecuritySettings() {
    val preferences = remember { Injekt.get<SecurityPreferences>() }
    val lock = remember { Injekt.get<AppLock>() }
    val incognito = remember { Injekt.get<Incognito>() }
    val useLock by remember { preferences.useAuthenticator() }.collectAsState()
    val scope = rememberCoroutineScope()
    var passwordDialog by remember { mutableStateOf(false) }
    var daysDialog by remember { mutableStateOf(false) }
    var timesDialog by remember { mutableStateOf(false) }
    var extensionsDialog by remember { mutableStateOf(false) }

    Column {
        SwitchSetting(localized(MR.strings.lock_with_biometrics), localized(DMR.strings.ask_for_a_password_when_zoukan), useLock) { on ->
            if (!on) {
                preferences.useAuthenticator().set(false)
                return@SwitchSetting
            }
            // A secret store call: off the UI thread, and a failure is said instead of crashing
            scope.launch {
                val hasPassword = secretStoreCall { lock.hasPassword() }.getOrElse {
                    Toasts.show(secretStoreError(it))
                    return@launch
                }
                if (hasPassword) preferences.useAuthenticator().set(true) else passwordDialog = true
            }
        }
        if (useLock) {
            SettingRow(localized(DMR.strings.change_password), onClick = { passwordDialog = true })
            ChoicePreference(
                localized(MR.strings.lock_when_idle),
                preferences.lockAppAfter(),
                listOf(0 to localized(MR.strings.lock_always), 1 to localized(DMR.strings.after_1_minute), 2 to localized(DMR.strings.after_2_minutes), 5 to localized(DMR.strings.after_5_minutes), 10 to localized(DMR.strings.after_10_minutes), -1 to localized(MR.strings.lock_never)),
            )
            val days by remember { preferences.authenticatorDays() }.collectAsState()
            SettingRow(
                localized(DMR.strings.lock_on_these_days),
                if (days == AppLock.LOCK_ALL_DAYS) localized(DMR.strings.every_day) else DAYS.filter { days and it.second != 0 }.joinToString { it.first }.ifEmpty { localized(DMR.strings.no_day) },
                onClick = { daysDialog = true },
            )
            val ranges by remember { preferences.authenticatorTimeRanges() }.collectAsState()
            SettingRow(
                localized(DMR.strings.lock_at_these_times),
                ranges.mapNotNull(AppLock::parseRange).joinToString { localized(DMR.strings.to, hhmm(it.start), hhmm(it.endInclusive)) }.ifEmpty { localized(DMR.strings.all_day) },
                onClick = { timesDialog = true },
            )
        }
        SwitchPreference(localized(MR.strings.hide_notification_content), preferences.hideNotificationContent(), localized(DMR.strings.notifications_say_only_that_there_is))
        SwitchPreference(localized(MR.strings.pref_incognito_mode), incognito.mode(), localized(DMR.strings.reading_keeps_no_history_progress_or))
        SettingRow(localized(DMR.strings.incognito_extensions), localized(DMR.strings.always_read_these_sources_in_incognito), onClick = { extensionsDialog = true })
    }

    if (passwordDialog) PasswordDialog(lock, preferences) { passwordDialog = false }
    if (daysDialog) DaysDialog(preferences) { daysDialog = false }
    if (timesDialog) TimesDialog(preferences) { timesDialog = false }
    if (extensionsDialog) IncognitoExtensionsDialog(incognito) { extensionsDialog = false }
}

/**
 * Runs a secret store call (and the password hash, slow on purpose) off the UI thread, as
 * LockOverlay does. A failure, for example access denied, comes back as a failed result.
 */
private suspend fun <T> secretStoreCall(block: () -> T): Result<T> =
    try {
        // NonCancellable: closing the dialog midway must not leave a password half changed
        Result.success(withContext(Dispatchers.Default + NonCancellable) { block() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppLock.logcat(LogPriority.WARN, e) { "App lock: the secret store failed" }
        Result.failure(e)
    }

private fun hhmm(minutes: Double): String = "%02d:%02d".format(minutes.toInt() / 60, minutes.toInt() % 60)

@Composable
private fun PasswordDialog(lock: AppLock, preferences: SecurityPreferences, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // Read from the secret store off the UI thread; null until it is known
    var hasPasswordState by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        secretStoreCall { lock.hasPassword() }
            .onSuccess { hasPasswordState = it }
            .onFailure { error = secretStoreError(it) }
    }
    val hasPassword = hasPasswordState == true
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPassword) localized(DMR.strings.change_the_password) else localized(DMR.strings.set_a_password)) },
        text = {
            Column {
                Text(
                    localized(DMR.strings.zoukan_asks_for_it_to_unlock),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (hasPassword) {
                    OutlinedTextField(current, { current = it }, label = { Text(localized(DMR.strings.current_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                OutlinedTextField(password, { password = it }, label = { Text(localized(DMR.strings.new_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(repeat, { repeat = it }, label = { Text(localized(DMR.strings.repeat_it)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = when {
                        password.length < 4 -> localized(DMR.strings.use_at_least_4_characters)
                        password != repeat -> localized(DMR.strings.the_passwords_differ)
                        else -> null
                    }
                    if (error != null) return@TextButton
                    busy = true
                    val check = hasPassword
                    val currentPassword = current
                    val newPassword = password
                    scope.launch {
                        secretStoreCall {
                            if (check && !lock.verify(currentPassword)) return@secretStoreCall false
                            lock.setPassword(newPassword)
                            preferences.useAuthenticator().set(true)
                            true
                        }.onSuccess { saved ->
                            if (saved) {
                                Toasts.show(localized(DMR.strings.password_set))
                                onDismiss()
                            } else {
                                error = localized(DMR.strings.the_current_password_is_wrong)
                            }
                        }.onFailure { error = secretStoreError(it) }
                        busy = false
                    }
                },
                enabled = hasPasswordState != null && !busy,
            ) { Text(localized(MR.strings.action_save)) }
        },
        dismissButton = {
            Row {
                if (hasPassword) {
                    TextButton(
                        onClick = {
                            busy = true
                            val currentPassword = current
                            scope.launch {
                                secretStoreCall {
                                    lock.verify(currentPassword).also { if (it) lock.clearPassword() }
                                }.onSuccess { removed ->
                                    if (removed) onDismiss() else error = localized(DMR.strings.enter_the_current_password_to_turn)
                                }.onFailure { error = secretStoreError(it) }
                                busy = false
                            }
                        },
                        enabled = !busy,
                    ) { Text(localized(DMR.strings.remove_the_lock)) }
                }
                TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) }
            }
        },
    )
}

@Composable
private fun DaysDialog(preferences: SecurityPreferences, onDismiss: () -> Unit) {
    var days by remember { mutableStateOf(preferences.authenticatorDays().get()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.lock_on_these_days)) },
        text = {
            Column {
                DAYS.forEach { (name, bit) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(days and bit != 0, onCheckedChange = { days = if (it) days or bit else days and bit.inv() })
                        Text(name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                preferences.authenticatorDays().set(days)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun TimesDialog(preferences: SecurityPreferences, onDismiss: () -> Unit) {
    val ranges = remember { mutableStateListOf(*preferences.authenticatorTimeRanges().get().toTypedArray()) }
    var start by remember { mutableStateOf("22:00") }
    var end by remember { mutableStateOf("23:59") }
    var error by remember { mutableStateOf<String?>(null) }
    fun minutes(text: String): Int? {
        val (h, m) = text.trim().split(":").takeIf { it.size == 2 } ?: return null
        val hour = h.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = m.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return hour * 60 + minute
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.lock_at_these_times)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScrollWithBar()) {
                Text(localized(DMR.strings.without_times_the_lock_works_all), style = MaterialTheme.typography.bodyMedium)
                ranges.toList().forEach { value ->
                    val range = AppLock.parseRange(value) ?: return@forEach
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(localized(DMR.strings.to, hhmm(range.start), hhmm(range.endInclusive)), Modifier.weight(1f))
                        TextButton(onClick = { ranges.remove(value) }) { Text(localized(MR.strings.action_remove)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(start, { start = it }, label = { Text(localized(DMR.strings.from)) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(end, { end = it }, label = { Text(localized(DMR.strings.to_2)) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val from = minutes(start)
                        val to = minutes(end)
                        error = if (from == null || to == null || to <= from) localized(DMR.strings.use_hh_mm_with_the_end) else null
                        if (from != null && to != null && to > from) ranges += "$from,$to"
                    }) { Text(localized(MR.strings.action_add)) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                preferences.authenticatorTimeRanges().set(ranges.toSet())
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun IncognitoExtensionsDialog(incognito: Incognito, onDismiss: () -> Unit) {
    val preference = remember { Injekt.get<SourcePreferences>().incognitoExtensions() }
    val selected by preference.collectAsState()
    val extensions = remember {
        listOf(LOCAL_SOURCE_PACKAGE to localized(MR.strings.action_display_local_badge), EH_PACKAGE to "E-Hentai") +
            Injekt.get<DesktopExtensionManager>().installedExtensionsFlow.value.values
                .sortedBy { it.name.lowercase() }
                .map { it.pkgName to it.name }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.incognito_extensions)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScrollWithBar()) {
                extensions.forEach { (pkg, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(pkg in selected, onCheckedChange = { incognito.setExtension(pkg, it) })
                        Text(name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(localized(DMR.strings.done)) } },
    )
}
