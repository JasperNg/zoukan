package zoukan.desktop.ui.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.reducedMotion
import kotlin.math.roundToInt

// ZOUKAN -->
/**
 * Komikku's Start / Resume button, shown while [visible]: draggable left and right over the
 * area it fills, and docking to the side its center ended on, as on Android. [atStart] is the
 * side it docks to now; [onMove] gets the side it was dropped on; [onOpen] runs on a click.
 *
 * It fills the chapter list's area (and takes no pointer events outside the button), so its
 * drag geometry is measured against that area and no caller has to track it.
 */
@Composable
internal fun ResumeButton(
    visible: Boolean,
    atStart: Boolean,
    started: Boolean,
    onMove: (atStart: Boolean) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    var areaX by remember { mutableFloatStateOf(0f) }
    var buttonSize by remember { mutableStateOf(IntSize.Zero) }
    var buttonX by remember { mutableFloatStateOf(0f) }
    val onMoveState by rememberUpdatedState(onMove)
    val reduceMotion by reducedMotion()
    Box(
        modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            areaSize = coordinates.size
            areaX = coordinates.positionInWindow().x
        },
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = if (reduceMotion) EnterTransition.None else fadeIn() + scaleIn(),
            exit = if (reduceMotion) ExitTransition.None else fadeOut() + scaleOut(),
            modifier = Modifier
                .align(if (atStart) Alignment.BottomStart else Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            ExtendedFloatingActionButton(
                onClick = onOpen,
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .onGloballyPositioned { coordinates ->
                        buttonSize = coordinates.size
                        buttonX = coordinates.positionInWindow().x
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                // Dock to the side the button's center ended on, as on Android
                                onMoveState(buttonX - areaX + buttonSize.width / 2f < areaSize.width / 2f)
                                offsetX = 0f
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val newOffsetX = offsetX + dragAmount
                            if (!newOffsetX.isNaN()) {
                                offsetX = newOffsetX
                            }
                        }
                    },
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(12.dp))
                Text(if (started) localized(MR.strings.action_resume) else localized(MR.strings.action_start))
            }
        }
    }
}
// ZOUKAN <--
