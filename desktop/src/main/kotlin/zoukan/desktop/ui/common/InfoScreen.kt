package zoukan.desktop.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Android's InfoScreen (the onboarding and What's new screens): an icon, a heading and a subtitle,
 * content that scrolls, and the accept button at the bottom. A window is wide, so the column is
 * kept to a readable width in the middle. The app's messages show above the buttons, not over
 * them (the onboarding covers the window, its messages included).
 */
@Composable
fun InfoScreen(
    icon: ImageVector,
    headingText: String,
    subtitleText: String,
    acceptText: String,
    onAcceptClick: () -> Unit,
    canAccept: Boolean = true,
    secondaryButton: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = BOTTOM_PADDING)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.size(16.dp))
            Text(headingText, style = MaterialTheme.typography.headlineLarge)
            Text(
                subtitleText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp).verticalScrollWithBar()) {
                content()
            }
            // The snackbar sits above the buttons and their bottom padding, as on Android, where it
            // belongs to the screen's Scaffold and never covers the screen's own buttons
            val density = LocalDensity.current
            var buttonsHeight by remember { mutableStateOf(0.dp) }
            ReserveSnackbarSpace(if (buttonsHeight > 0.dp) buttonsHeight + BOTTOM_PADDING else 0.dp)
            Row(
                Modifier.fillMaxWidth().onSizeChanged { buttonsHeight = with(density) { it.height.toDp() } },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                secondaryButton()
                Spacer(Modifier.weight(1f))
                Button(onClick = onAcceptClick, enabled = canAccept, modifier = Modifier.widthIn(min = 160.dp)) { Text(acceptText) }
            }
        }
    }
}

private val BOTTOM_PADDING = 24.dp
