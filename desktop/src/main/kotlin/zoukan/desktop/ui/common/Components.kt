package zoukan.desktop.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.SubcomposeAsyncImage
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized

/** A cover with Coil, a placeholder while loading and an icon when it fails. */
@Composable
fun CoverImage(
    cover: Any?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onLoaded: ((width: Int, height: Int) -> Unit)? = null,
) {
    SubcomposeAsyncImage(
        model = cover,
        imageLoader = Images.loader,
        contentDescription = null,
        contentScale = contentScale,
        onSuccess = onLoaded?.let { { state -> it(state.result.image.width, state.result.image.height) } },
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        loading = { Box(Modifier.fillMaxSize()) },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** A grid cell: cover with the title over a gradient, and optional badges. */
@Composable
fun MangaGridItem(
    title: String,
    cover: Cover?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    dimmed: Boolean = false,
    onSecondaryClick: (() -> Unit)? = null,
) {
    Box(
        modifier
            .testTag("manga-item")
            .aspectRatio(2f / 3f)
            .clip(Corners.Medium)
            .coverOutline(Corners.Medium)
            .clickable(onClick = onClick)
            .then(if (onSecondaryClick != null) Modifier.onRightClick(onSecondaryClick) else Modifier),
    ) {
        CoverImage(cover, Modifier.fillMaxSize())
        if (dimmed) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        ) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = Corners.OnCover,
                modifier = Modifier.padding(Corners.CoverInset).align(Alignment.TopStart),
            ) {
                Text(
                    badge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun EmptyScreen(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** A screen header: back button (when there is somewhere to go back to), title and actions. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showBack: Boolean = true,
    color: Color = MaterialTheme.colorScheme.surface,
    actions: @Composable () -> Unit = {},
) {
    val navigator = LocalNavigator.currentOrThrow
    Surface(color = color, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack && navigator.canPop) {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = localized(MR.strings.action_webview_back))
                }
            } else {
                Spacer(Modifier.size(8.dp))
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}

/** An icon button with a tooltip, the desktop way to name an icon. */
@Composable
fun TooltipIconButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Tooltip(label) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
    }
}

/** [Tooltip] only while [show], for a control whose words can be turned off. */
@Composable
fun TooltipIf(show: Boolean, label: String, content: @Composable () -> Unit) {
    if (show) Tooltip(label, content = content) else content()
}

/** Shows [label] when the mouse rests on [content], for controls that are an icon without words. */
@Composable
fun Tooltip(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // On desktop TooltipBox puts its modifier on an inner anchor, so a parent's Modifier.align
    // never reaches the parent Box; this Box takes [modifier] where the parent sees it
    Box(modifier, propagateMinConstraints = true) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
            content = content,
        )
    }
}

/**
 * A hairline around a cover, so a cover whose edge has the window's color still has an edge:
 * black on light themes and white on dark ones, never a theme color, which would tint the picture.
 */
@Composable
fun Modifier.coverOutline(shape: Shape): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return border(1.dp, (if (dark) Color.White else Color.Black).copy(alpha = 0.1f), shape)
}
