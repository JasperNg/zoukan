package zoukan.desktop.ui.common

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/**
 * Calls [onClick] on a secondary (right) mouse click. The same action is a semantics custom action
 * named [label], so a screen reader or keyboard user (and the tests) can reach what only a mouse
 * could: see [onRightClickAt].
 */
fun Modifier.onRightClick(onClick: () -> Unit): Modifier = onRightClickAt(null) { onClick() }

/** [onRightClick], with [label] naming the action for screen readers and keyboard users. */
fun Modifier.onRightClick(label: String, onClick: () -> Unit): Modifier = onRightClickAt(label) { onClick() }

/**
 * Calls [onClick] on a secondary (right) mouse click, with where it landed in this element. The
 * action is also a semantics custom action named [label] ("More actions" when null), which runs it
 * at the element's top left corner, for screen readers, keyboard users and the button crawl.
 */
fun Modifier.onRightClickAt(onClick: (Offset) -> Unit): Modifier = onRightClickAt(null, onClick)

/** [onRightClickAt], with [label] naming the action for screen readers and keyboard users. */
fun Modifier.onRightClickAt(label: String?, onClick: (Offset) -> Unit): Modifier = this
    .moreActions(label) { onClick(Offset.Zero) }
    .onPointerEvent(PointerEventType.Press) { event ->
        if (event.button == PointerButton.Secondary) onClick(event.changes.firstOrNull()?.position ?: Offset.Zero)
    }

/**
 * Only the semantics half of [onRightClickAt]: a custom action named [label] ("More actions" when
 * null) that runs [onAction], for an element that handles the right-click itself.
 */
fun Modifier.moreActions(label: String? = null, onAction: () -> Unit): Modifier = semantics {
    customActions = listOf(
        CustomAccessibilityAction(label ?: localized(DMR.strings.more_actions)) {
            onAction()
            true
        },
    )
}

/**
 * A row with a checkbox or switch in it: the whole row is the control, with the row's text as its
 * label. Give the checkbox or switch `onCheckedChange = null`, so there is one node to announce,
 * not an unlabelled second one.
 */
fun Modifier.checkRow(checked: Boolean, enabled: Boolean = true, role: Role = Role.Checkbox, onChange: (Boolean) -> Unit): Modifier =
    toggleable(value = checked, enabled = enabled, role = role, onValueChange = onChange)

/**
 * A menu item with a checkbox in it, which the item's own click toggles: gives the item the
 * checkbox's role and state, which a checkbox without a callback doesn't announce. The menu-item
 * form of [checkRow], for a DropdownMenuItem that already handles the click.
 */
fun Modifier.checkedMenuItem(checked: Boolean): Modifier = semantics {
    role = Role.Checkbox
    toggleableState = ToggleableState(checked)
}

/** The menu-item form of [radioRow]: a DropdownMenuItem with a radio button in it, which its click selects. */
fun Modifier.selectedMenuItem(selected: Boolean): Modifier = semantics {
    role = Role.RadioButton
    this.selected = selected
}

/** A row with a radio button in it: the whole row is the choice. Give the radio button `onClick = null`. */
fun Modifier.radioRow(selected: Boolean, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
