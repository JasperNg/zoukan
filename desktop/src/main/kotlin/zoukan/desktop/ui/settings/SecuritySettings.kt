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
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_add
import tachiyomi.i18n.action_cancel
import tachiyomi.i18n.action_display_local_badge
import tachiyomi.i18n.action_ok
import tachiyomi.i18n.action_remove
import tachiyomi.i18n.action_save
import tachiyomi.i18n.hide_notification_content
import tachiyomi.i18n.lock_always
import tachiyomi.i18n.lock_never
import tachiyomi.i18n.lock_when_idle
import tachiyomi.i18n.lock_with_biometrics
import tachiyomi.i18n.pref_category_security
import tachiyomi.i18n.pref_incognito_mode
import tachiyomi.i18n.secure_screen
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.friday
import tachiyomi.i18n.sy.monday
import tachiyomi.i18n.sy.saturday
import tachiyomi.i18n.sy.sunday
import tachiyomi.i18n.sy.thursday
import tachiyomi.i18n.sy.tuesday
import tachiyomi.i18n.sy.wednesday
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.security.AppLock
import zoukan.desktop.security.Incognito
import zoukan.desktop.ui.common.collectAsState
import zoukan.desktop.ui.common.verticalScrollWithBar
import zoukan.desktop.ui.security.secretStoreError
import zoukan.extension.DesktopExtensionManager
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.after_10_minutes
import zoukan.i18n.desktop.after_1_minute
import zoukan.i18n.desktop.after_2_minutes
import zoukan.i18n.desktop.after_5_minutes
import zoukan.i18n.desktop.always_read_these_sources_in_incognito
import zoukan.i18n.desktop.ask_for_a_password_when_zoukan
import zoukan.i18n.desktop.change_password
import zoukan.i18n.desktop.change_the_password
import zoukan.i18n.desktop.current_password
import zoukan.i18n.desktop.done
import zoukan.i18n.desktop.enter_the_current_password_to_turn
import zoukan.i18n.desktop.from
import zoukan.i18n.desktop.incognito_extensions
import zoukan.i18n.desktop.lock_at_these_times
import zoukan.i18n.desktop.lock_on_these_days
import zoukan.i18n.desktop.new_password
import zoukan.i18n.desktop.notifications_say_only_that_there_is
import zoukan.i18n.desktop.password_set
import zoukan.i18n.desktop.reading_keeps_no_history_progress_or
import zoukan.i18n.desktop.remove_the_lock
import zoukan.i18n.desktop.repeat_it
import zoukan.i18n.desktop.set_a_password
import zoukan.i18n.desktop.the_current_password_is_wrong
import zoukan.i18n.desktop.to
import zoukan.i18n.desktop.to_2
import zoukan.i18n.desktop.without_times_the_lock_works_all
import zoukan.i18n.desktop.zoukan_asks_for_it_to_unlock

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
    val controller = remember(preferences, lock, incognito) {
        SecuritySettingsController(preferences, lock, incognito)
    }
    val useLock by remember { preferences.useAuthenticator() }.collectAsState()
    val scope = rememberCoroutineScope()
    var passwordDialog by remember { mutableStateOf(false) }
    var daysDialog by remember { mutableStateOf(false) }
    var timesDialog by remember { mutableStateOf(false) }
    var extensionsDialog by remember { mutableStateOf(false) }

    Column {
        SwitchSetting(localized(MR.strings.lock_with_biometrics), localized(DMR.strings.ask_for_a_password_when_zoukan), useLock) { on ->
            scope.launch {
                when (val result = controller.toggleLock(on)) {
                    SecuritySettingsController.ToggleLockResult.Disabled,
                    SecuritySettingsController.ToggleLockResult.Enabled,
                    -> Unit
                    SecuritySettingsController.ToggleLockResult.NeedsPasswordSetup -> passwordDialog = true
                    is SecuritySettingsController.ToggleLockResult.SecretStoreFailed -> Toasts.show(result.message)
                }
            }
        }
        if (useLock) {
            SettingRow(localized(DMR.strings.change_password), onClick = { passwordDialog = true })
            ChoicePreference(
                localized(MR.strings.lock_when_idle),
                preferences.lockAppAfter(),
                listOf(
                    0 to localized(MR.strings.lock_always),
                    1 to localized(DMR.strings.after_1_minute),
                    2 to localized(DMR.strings.after_2_minutes),
                    5 to localized(DMR.strings.after_5_minutes),
                    10 to localized(DMR.strings.after_10_minutes),
                    -1 to localized(MR.strings.lock_never),
                ),
            )
            val days by remember { preferences.authenticatorDays() }.collectAsState()
            SettingRow(
                localized(DMR.strings.lock_on_these_days),
                SecuritySettingsController.daysSummary(days, DAYS),
                onClick = { daysDialog = true },
            )
            val ranges by remember { preferences.authenticatorTimeRanges() }.collectAsState()
            SettingRow(
                localized(DMR.strings.lock_at_these_times),
                SecuritySettingsController.timeRangesSummary(ranges),
                onClick = { timesDialog = true },
            )
        }
        // Leaves the windows out of screenshots and screen sharing (see SecureScreen)
        ChoicePreference(
            localized(MR.strings.secure_screen),
            preferences.secureScreen(),
            SecurityPreferences.SecureScreenMode.entries.map { it to localized(it.titleRes) },
        )
        SwitchPreference(localized(MR.strings.hide_notification_content), preferences.hideNotificationContent(), localized(DMR.strings.notifications_say_only_that_there_is))
        SwitchPreference(localized(MR.strings.pref_incognito_mode), incognito.mode(), localized(DMR.strings.reading_keeps_no_history_progress_or))
        SettingRow(localized(DMR.strings.incognito_extensions), localized(DMR.strings.always_read_these_sources_in_incognito), onClick = { extensionsDialog = true })
    }

    if (passwordDialog) PasswordDialog(controller) { passwordDialog = false }
    if (daysDialog) DaysDialog(controller, preferences) { daysDialog = false }
    if (timesDialog) TimesDialog(controller, preferences) { timesDialog = false }
    if (extensionsDialog) IncognitoExtensionsDialog(controller) { extensionsDialog = false }
}

@Composable
private fun PasswordDialog(controller: SecuritySettingsController, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // Read from the secret store off the UI thread; null until it is known
    var hasPasswordState by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(controller) {
        controller.hasPassword()
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
                    val validationError = controller.validateNewPassword(password, repeat)
                    if (validationError != null) {
                        error = validationError
                        return@TextButton
                    }
                    error = null
                    busy = true
                    val check = hasPassword
                    val currentPassword = current
                    val newPassword = password
                    val repeatPassword = repeat
                    scope.launch {
                        // finally: a fatal error going on from the call still frees the buttons
                        try {
                            when (val result = controller.savePassword(check, currentPassword, newPassword, repeatPassword)) {
                                SecuritySettingsController.PasswordActionResult.Saved -> {
                                    Toasts.show(localized(DMR.strings.password_set))
                                    onDismiss()
                                }
                                SecuritySettingsController.PasswordActionResult.WrongCurrentPassword ->
                                    error = localized(DMR.strings.the_current_password_is_wrong)
                                is SecuritySettingsController.PasswordActionResult.ValidationError ->
                                    error = result.message
                                is SecuritySettingsController.PasswordActionResult.SecretStoreError ->
                                    error = result.message
                                SecuritySettingsController.PasswordActionResult.Removed -> Unit
                            }
                        } finally {
                            busy = false
                        }
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
                                try {
                                    when (val result = controller.removePassword(currentPassword)) {
                                        SecuritySettingsController.PasswordActionResult.Removed -> onDismiss()
                                        SecuritySettingsController.PasswordActionResult.WrongCurrentPassword ->
                                            error = localized(DMR.strings.enter_the_current_password_to_turn)
                                        is SecuritySettingsController.PasswordActionResult.SecretStoreError ->
                                            error = result.message
                                        else -> Unit
                                    }
                                } finally {
                                    busy = false
                                }
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
private fun DaysDialog(controller: SecuritySettingsController, preferences: SecurityPreferences, onDismiss: () -> Unit) {
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
                controller.saveDays(days)
                onDismiss()
            }) { Text(localized(MR.strings.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun TimesDialog(controller: SecuritySettingsController, preferences: SecurityPreferences, onDismiss: () -> Unit) {
    val ranges = remember { mutableStateListOf(*preferences.authenticatorTimeRanges().get().toTypedArray()) }
    var start by remember { mutableStateOf("22:00") }
    var end by remember { mutableStateOf("23:59") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.lock_at_these_times)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScrollWithBar()) {
                Text(localized(DMR.strings.without_times_the_lock_works_all), style = MaterialTheme.typography.bodyMedium)
                ranges.toList().forEach { value ->
                    val range = AppLock.parseRange(value) ?: return@forEach
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            localized(DMR.strings.to, SecuritySettingsController.formatHhmm(range.start), SecuritySettingsController.formatHhmm(range.endInclusive)),
                            Modifier.weight(1f),
                        )
                        TextButton(onClick = { ranges.remove(value) }) { Text(localized(MR.strings.action_remove)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(start, { start = it }, label = { Text(localized(DMR.strings.from)) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(end, { end = it }, label = { Text(localized(DMR.strings.to_2)) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        when (val added = controller.addTimeRange(start, end)) {
                            is SecuritySettingsController.TimeRangeResult.Added -> {
                                error = null
                                ranges += added.range
                            }
                            is SecuritySettingsController.TimeRangeResult.Invalid -> error = added.message
                        }
                    }) { Text(localized(MR.strings.action_add)) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                controller.saveTimeRanges(ranges)
                onDismiss()
            }) { Text(localized(MR.strings.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun IncognitoExtensionsDialog(controller: SecuritySettingsController, onDismiss: () -> Unit) {
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
                        Checkbox(pkg in selected, onCheckedChange = { controller.setExtensionIncognito(pkg, it) })
                        Text(name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(localized(DMR.strings.done)) } },
    )
}
