package zoukan.desktop.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.OnShortcut
import zoukan.desktop.ui.Shortcuts
import zoukan.i18n.desktop.DMR

/**
 * A screen's search box. Ctrl/Cmd + F puts the cursor in it, the X button or Escape empties it,
 * and Enter runs [onSearch] where the search is not live.
 *
 * A [filter] field only narrows the list under it, so it looks different from a search: a filled
 * field with a filter icon instead of the outlined one with a magnifier.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    filter: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    OnShortcut { action ->
        if (action == Shortcuts.Action.Find) {
            runCatching { focus.requestFocus() }.isSuccess
        } else {
            false
        }
    }
    val placeholderText: @Composable () -> Unit = { Text(placeholder, maxLines = 1) }
    val leading: @Composable () -> Unit = { Icon(if (filter) Icons.Outlined.FilterList else Icons.Outlined.Search, null) }
    val trailing: (@Composable () -> Unit)? = if (value.isEmpty()) {
        null
    } else {
        {
            IconButton(onClick = { onValueChange("") }) {
                Icon(Icons.Outlined.Close, contentDescription = localized(DMR.strings.clear_search), modifier = Modifier.size(18.dp))
            }
        }
    }
    val fieldModifier = modifier.focusRequester(focus).onPreviewKeyEvent { event ->
        when {
            event.type != KeyEventType.KeyDown -> false
            event.key == Key.Escape && value.isNotEmpty() -> {
                onValueChange("")
                true
            }
            (event.key == Key.Enter || event.key == Key.NumPadEnter) && onSearch != null -> {
                onSearch()
                true
            }
            else -> false
        }
    }
    if (filter) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholderText,
            leadingIcon = leading,
            trailingIcon = trailing,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = fieldModifier,
        )
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholderText,
            leadingIcon = leading,
            trailingIcon = trailing,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = fieldModifier,
        )
    }
}
