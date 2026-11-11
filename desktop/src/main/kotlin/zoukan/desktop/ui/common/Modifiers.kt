package zoukan.desktop.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/** Calls [onClick] on a secondary (right) mouse click. */
fun Modifier.onRightClick(onClick: () -> Unit): Modifier = onRightClickAt { onClick() }

/** Calls [onClick] on a secondary (right) mouse click, with where it landed in this element. */
fun Modifier.onRightClickAt(onClick: (Offset) -> Unit): Modifier = onPointerEvent(PointerEventType.Press) { event ->
    if (event.button == PointerButton.Secondary) onClick(event.changes.firstOrNull()?.position ?: Offset.Zero)
}
