package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import exh.source.EH_PACKAGE
import exh.source.LOCAL_SOURCE_PACKAGE
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.security.AppLock
import zoukan.desktop.security.Incognito
import zoukan.desktop.ui.common.Toasts
import zoukan.extension.DesktopExtensionManager

/** Settings > Security and privacy: the app lock and incognito mode, as on Android. */
fun LazyListScope.securitySettings() {
    settingsGroup("Security and privacy")
    item(key = "security") { SecuritySettings() }
}

private val DAYS = listOf(
    "Monday" to AppLock.LOCK_MONDAY,
    "Tuesday" to AppLock.LOCK_TUESDAY,
    "Wednesday" to AppLock.LOCK_WEDNESDAY,
    "Thursday" to AppLock.LOCK_THURSDAY,
    "Friday" to AppLock.LOCK_FRIDAY,
    "Saturday" to AppLock.LOCK_SATURDAY,
    "Sunday" to AppLock.LOCK_SUNDAY,
)

@Composable
private fun SecuritySettings() {
    val preferences = remember { Injekt.get<SecurityPreferences>() }
    val lock = remember { Injekt.get<AppLock>() }
    val incognito = remember { Injekt.get<Incognito>() }
    val useLock by preferences.useAuthenticator().changes().collectAsState(preferences.useAuthenticator().get())
    var passwordDialog by remember { mutableStateOf(false) }
    var daysDialog by remember { mutableStateOf(false) }
    var timesDialog by remember { mutableStateOf(false) }
    var extensionsDialog by remember { mutableStateOf(false) }

    Column {
        SwitchSetting("Require unlock", "Ask for a password when Zoukan opens and after it was away", useLock) { on ->
            if (on && !lock.hasPassword()) passwordDialog = true else preferences.useAuthenticator().set(on)
        }
        if (useLock) {
            SettingRow("Change password", onClick = { passwordDialog = true })
            ChoicePreference(
                "Lock when idle",
                preferences.lockAppAfter(),
                listOf(0 to "Always", 1 to "After 1 minute", 2 to "After 2 minutes", 5 to "After 5 minutes", 10 to "After 10 minutes", -1 to "Never"),
            )
            val days by preferences.authenticatorDays().changes().collectAsState(preferences.authenticatorDays().get())
            SettingRow(
                "Lock on these days",
                if (days == AppLock.LOCK_ALL_DAYS) "Every day" else DAYS.filter { days and it.second != 0 }.joinToString { it.first }.ifEmpty { "No day" },
                onClick = { daysDialog = true },
            )
            val ranges by preferences.authenticatorTimeRanges().changes().collectAsState(preferences.authenticatorTimeRanges().get())
            SettingRow(
                "Lock at these times",
                ranges.mapNotNull(AppLock::parseRange).joinToString { "${hhmm(it.start)} to ${hhmm(it.endInclusive)}" }.ifEmpty { "All day" },
                onClick = { timesDialog = true },
            )
        }
        SwitchPreference("Hide notification content", preferences.hideNotificationContent(), "Notifications say only that there is news")
        SwitchPreference("Incognito mode", incognito.mode(), "Reading keeps no history, progress or tracking")
        SettingRow("Incognito extensions", "Always read these sources in incognito", onClick = { extensionsDialog = true })
    }

    if (passwordDialog) PasswordDialog(lock, preferences) { passwordDialog = false }
    if (daysDialog) DaysDialog(preferences) { daysDialog = false }
    if (timesDialog) TimesDialog(preferences) { timesDialog = false }
    if (extensionsDialog) IncognitoExtensionsDialog(incognito) { extensionsDialog = false }
}

private fun hhmm(minutes: Double): String = "%02d:%02d".format(minutes.toInt() / 60, minutes.toInt() % 60)

@Composable
private fun PasswordDialog(lock: AppLock, preferences: SecurityPreferences, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val hasPassword = remember { lock.hasPassword() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPassword) "Change the password" else "Set a password") },
        text = {
            Column {
                Text(
                    "Zoukan asks for it to unlock. It is kept, hashed, in the system's secret store; there is no way to recover it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (hasPassword) {
                    OutlinedTextField(current, { current = it }, label = { Text("Current password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                OutlinedTextField(password, { password = it }, label = { Text("New password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(repeat, { repeat = it }, label = { Text("Repeat it") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    hasPassword && !lock.verify(current) -> "The current password is wrong"
                    password.length < 4 -> "Use at least 4 characters"
                    password != repeat -> "The passwords differ"
                    else -> null
                }
                if (error == null) {
                    lock.setPassword(password)
                    preferences.useAuthenticator().set(true)
                    Toasts.show("Password set")
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (hasPassword) {
                    TextButton(onClick = {
                        if (lock.verify(current)) {
                            lock.clearPassword()
                            onDismiss()
                        } else {
                            error = "Enter the current password to turn the lock off"
                        }
                    }) { Text("Remove the lock") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun DaysDialog(preferences: SecurityPreferences, onDismiss: () -> Unit) {
    var days by remember { mutableStateOf(preferences.authenticatorDays().get()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lock on these days") },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        title = { Text("Lock at these times") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Without times the lock works all day.", style = MaterialTheme.typography.bodyMedium)
                ranges.toList().forEach { value ->
                    val range = AppLock.parseRange(value) ?: return@forEach
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${hhmm(range.start)} to ${hhmm(range.endInclusive)}", Modifier.weight(1f))
                        TextButton(onClick = { ranges.remove(value) }) { Text("Remove") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(start, { start = it }, label = { Text("From") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(end, { end = it }, label = { Text("To") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val from = minutes(start)
                        val to = minutes(end)
                        error = if (from == null || to == null || to <= from) "Use HH:MM, with the end after the start" else null
                        if (from != null && to != null && to > from) ranges += "$from,$to"
                    }) { Text("Add") }
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IncognitoExtensionsDialog(incognito: Incognito, onDismiss: () -> Unit) {
    val preference = remember { Injekt.get<SourcePreferences>().incognitoExtensions() }
    val selected by preference.changes().collectAsState(preference.get())
    val extensions = remember {
        listOf(LOCAL_SOURCE_PACKAGE to "Local source", EH_PACKAGE to "E-Hentai") +
            Injekt.get<DesktopExtensionManager>().installedExtensionsFlow.value.values
                .sortedBy { it.name.lowercase() }
                .map { it.pkgName to it.name }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Incognito extensions") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                extensions.forEach { (pkg, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(pkg in selected, onCheckedChange = { incognito.setExtension(pkg, it) })
                        Text(name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
