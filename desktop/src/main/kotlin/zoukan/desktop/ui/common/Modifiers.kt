package zoukan.desktop.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/** Calls [onClick] on a secondary (right) mouse click. */
fun Modifier.onRightClick(onClick: () -> Unit): Modifier = onPointerEvent(PointerEventType.Press) { event ->
    if (event.button == PointerButton.Secondary) onClick()
}
