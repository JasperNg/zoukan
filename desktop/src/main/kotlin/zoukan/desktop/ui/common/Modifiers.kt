package zoukan.desktop.ui.common

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role

/** Calls [onClick] on a secondary (right) mouse click. */
fun Modifier.onRightClick(onClick: () -> Unit): Modifier = onRightClickAt { onClick() }

/** Calls [onClick] on a secondary (right) mouse click, with where it landed in this element. */
fun Modifier.onRightClickAt(onClick: (Offset) -> Unit): Modifier = onPointerEvent(PointerEventType.Press) { event ->
    if (event.button == PointerButton.Secondary) onClick(event.changes.firstOrNull()?.position ?: Offset.Zero)
}

/**
 * A row with a checkbox or switch in it: the whole row is the control, with the row's text as its
 * label. Give the checkbox or switch `onCheckedChange = null`, so there is one node to announce,
 * not an unlabelled second one.
 */
fun Modifier.checkRow(checked: Boolean, enabled: Boolean = true, role: Role = Role.Checkbox, onChange: (Boolean) -> Unit): Modifier =
    toggleable(value = checked, enabled = enabled, role = role, onValueChange = onChange)

/** A row with a radio button in it: the whole row is the choice. Give the radio button `onClick = null`. */
fun Modifier.radioRow(selected: Boolean, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
