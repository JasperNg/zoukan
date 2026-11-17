package zoukan.desktop.ui.common

import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState

// The controls of a [checkRow] or [radioRow]. The row is the control, so the checkbox, switch or
// radio button has no callback of its own (the `Nothing?` parameter keeps it null). Material then
// drops its 48 dp touch target, and the row would shrink and its label touch the control: these keep
// the 48 dp box, so a row keeps the height and spacing it has with a clickable control.

@Composable
fun RowCheckbox(checked: Boolean, onCheckedChange: Nothing?, enabled: Boolean = true) =
    Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.minimumInteractiveComponentSize(), enabled = enabled)

@Composable
fun RowTriStateCheckbox(state: ToggleableState, onClick: Nothing?, enabled: Boolean = true) =
    TriStateCheckbox(state = state, onClick = onClick, modifier = Modifier.minimumInteractiveComponentSize(), enabled = enabled)

@Composable
fun RowSwitch(checked: Boolean, onCheckedChange: Nothing?, enabled: Boolean = true) =
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.minimumInteractiveComponentSize(), enabled = enabled)

@Composable
fun RowRadioButton(selected: Boolean, onClick: Nothing?, enabled: Boolean = true) =
    RadioButton(selected = selected, onClick = onClick, modifier = Modifier.minimumInteractiveComponentSize(), enabled = enabled)
