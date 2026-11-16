package zoukan.desktop.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized

// ZOUKAN -->
/**
 * A second question before something the user cannot take back or that runs third-party code: a
 * title, what will happen to what (when [text] is not null), and a confirm button in the error
 * colour. Cancel has the focus, so Enter or Space on a freshly opened dialog keeps everything as
 * it is.
 *
 * [onDismiss] runs for Cancel, Escape, a click outside and after a confirm; [onConfirm] runs only
 * for the confirm button, after [onDismiss].
 */
@Composable
fun ConfirmDestructiveDialog(
    title: String,
    text: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = {
            TextButton(
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    onDismiss()
                    onConfirm()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(localized(MR.strings.action_cancel))
            }
        },
    )
}
// ZOUKAN <--
