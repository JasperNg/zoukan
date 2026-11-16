package zoukan.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import zoukan.desktop.app.Toasts
import zoukan.desktop.compat.localized
import zoukan.desktop.download.CbzPassword
import zoukan.desktop.ui.common.ConfirmDestructiveDialog
import zoukan.i18n.desktop.DMR

/** The change the user asked for, waiting for a second confirmation. */
private enum class PendingChange { Save, Remove }

/**
 * Sets, changes or removes the password new CBZ downloads are encrypted with. Chapters already
 * downloaded stay encrypted with the password they were made with, so changing or removing a
 * password that is set asks first; a new password is typed twice.
 */
@Composable
internal fun DownloadPasswordDialog(
    cbzPassword: CbzPassword,
    protectDownloads: Preference<Boolean>,
    hasPassword: Boolean,
    onPasswordChanged: (hasPassword: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PendingChange?>(null) }

    fun save() {
        cbzPassword.set(password)
        onPasswordChanged(true)
        onDismiss()
        Toasts.show(localized(DMR.strings.download_password_saved))
    }

    fun remove() {
        cbzPassword.set("")
        protectDownloads.set(false)
        onPasswordChanged(false)
        onDismiss()
    }

    when (pending) {
        PendingChange.Save -> ConfirmDestructiveDialog(
            title = localized(DMR.strings.download_password),
            text = localized(DMR.strings.download_password_change_warning),
            confirmLabel = localized(MR.strings.action_save),
            onConfirm = ::save,
            onDismiss = { pending = null },
        )
        PendingChange.Remove -> ConfirmDestructiveDialog(
            title = localized(DMR.strings.download_password),
            text = localized(DMR.strings.download_password_change_warning),
            confirmLabel = localized(MR.strings.action_remove),
            onConfirm = ::remove,
            onDismiss = { pending = null },
        )
        null -> Unit
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(DMR.strings.download_password)) },
        text = {
            Column {
                OutlinedTextField(
                    password,
                    { password = it },
                    modifier = Modifier.fillMaxWidth().testTag("download-password"),
                    singleLine = true,
                    label = { Text(localized(MR.strings.password)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    repeat,
                    { repeat = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("download-password-repeat"),
                    singleLine = true,
                    label = { Text(localized(DMR.strings.repeat_the_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (repeat.isNotEmpty() && repeat != password) {
                    Text(
                        localized(DMR.strings.passwords_do_not_match),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() && password == repeat, onClick = {
                // Chapters downloaded with the current password need it: a different one asks first
                val current = cbzPassword.get()?.concatToString()
                if (hasPassword && current != password) pending = PendingChange.Save else save()
            }) { Text(localized(MR.strings.action_save)) }
        },
        dismissButton = {
            Row {
                if (hasPassword) {
                    TextButton(onClick = { pending = PendingChange.Remove }) { Text(localized(MR.strings.action_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) }
            }
        },
    )
}
