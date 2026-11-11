package zoukan.desktop.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round

/** A right-click menu: open while [position] is set, at that point of the item that was clicked. */
@Stable
class ContextMenuState {
    var position: Offset? by mutableStateOf(null)
        private set

    /** Where the menu last opened, so it stays in place while it closes. */
    internal var last: Offset by mutableStateOf(Offset.Zero)
        private set

    val isOpen: Boolean get() = position != null

    fun open(at: Offset) {
        last = at
        position = at
    }

    fun close() {
        position = null
    }
}

@Composable
fun rememberContextMenuState(): ContextMenuState = remember { ContextMenuState() }

/**
 * The menu [state] opens, at the pointer. Put it in a Box that has the same top left corner as
 * the element the right click was taken from, so the click position lines up.
 */
@Composable
fun BoxScope.ContextMenu(state: ContextMenuState, onDismiss: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    // A zero-size anchor at the pointer: the menu opens below it, or above when there is no room
    Box(Modifier.align(Alignment.TopStart).offset { state.last.round() }.size(0.dp)) {
        DropdownMenu(
            expanded = state.isOpen,
            onDismissRequest = {
                state.close()
                onDismiss()
            },
        ) { content() }
    }
}

/** A context menu row that closes the menu, then runs [onClick]. */
@Composable
fun ContextMenuItem(state: ContextMenuState, text: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        enabled = enabled,
        onClick = {
            state.close()
            onClick()
        },
    )
}

@Composable
fun ContextMenuDivider() = HorizontalDivider(Modifier.padding(vertical = 4.dp))
