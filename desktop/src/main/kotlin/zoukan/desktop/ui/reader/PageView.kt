package zoukan.desktop.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_retry
import tachiyomi.i18n.missing_chapters_warning
import tachiyomi.i18n.nav_zone_next
import zoukan.desktop.compat.localized
import zoukan.desktop.compat.localizedPlural
import zoukan.i18n.desktop.DMR
import zoukan.i18n.desktop.current
import zoukan.i18n.desktop.finished
import zoukan.i18n.desktop.page_could_not_load
import zoukan.i18n.desktop.previous
import zoukan.i18n.desktop.read_next_chapter
import zoukan.i18n.desktop.read_previous_chapter
import zoukan.i18n.desktop.there_is_no_next_chapter
import zoukan.i18n.desktop.there_is_no_previous_chapter

/** Tells [image] that this composable draws it, so a released image keeps its pixels until this leaves */
@Composable
internal fun DrawsImage(image: PageImage) {
    androidx.compose.runtime.DisposableEffect(image) {
        image.attach()
        onDispose { image.detach() }
    }
}

/** A decoded page drawn at its own aspect ratio: the slices stacked, filling the width it gets. */
@Composable
fun PageSlices(image: PageImage, modifier: Modifier = Modifier) {
    // ZOUKAN -->
    DrawsImage(image)
    PlayAnimation(image)
    // ZOUKAN <--
    Column(modifier.testTag("page-image")) {
        image.slices.forEach { slice ->
            Image(
                bitmap = slice,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun PageLoading(number: Int, color: Color, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = color)
            Spacer(Modifier.height(8.dp))
            Text("$number", color = color, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PageError(number: Int, message: String, color: Color, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp).padding(16.dp)) {
            Text(localized(DMR.strings.page_could_not_load, number), color = color, style = MaterialTheme.typography.titleMedium)
            Text(message, color = color.copy(alpha = 0.7f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onRetry) { Text(localized(MR.strings.action_retry)) }
        }
    }
}

/**
 * Shown before the first and after the last page: where the reader goes next. It warns about the
 * chapters missing between the two, as Android's ChapterTransition.
 */
@Composable
fun ChapterTransition(
    current: Chapter?,
    other: Chapter?,
    next: Boolean,
    color: Color,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ZOUKAN -->
    val missing = ChapterTransitions.gap(current, other, next)
    // ZOUKAN <--
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (current != null) {
                Text(if (next) localized(DMR.strings.finished) else localized(DMR.strings.current), color = color.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                Text(current.name, color = color, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            if (other == null) {
                Text(
                    if (next) localized(DMR.strings.there_is_no_next_chapter) else localized(DMR.strings.there_is_no_previous_chapter),
                    color = color,
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Text(if (next) localized(MR.strings.nav_zone_next) else localized(DMR.strings.previous), color = color.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                Text(other.name, color = color, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                // ZOUKAN -->
                if (missing > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        localizedPlural(MR.plurals.missing_chapters_warning, missing, missing),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 420.dp),
                    )
                }
                // ZOUKAN <--
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onGo) { Text(if (next) localized(DMR.strings.read_next_chapter) else localized(DMR.strings.read_previous_chapter)) }
            }
        }
    }
}

// ZOUKAN -->
/**
 * The reader's background for a reader theme. Automatic (3) is gray in a dark app theme and white
 * in a light one, as Android's (which follows night mode); its pages may set their own (PageBackground).
 */
fun readerBackground(theme: Int, dark: Boolean = false): Color = when (theme) {
    0 -> Color.White
    2 -> Color(0xFF202125)
    3 -> if (dark) Color(0xFF202125) else Color.White
    else -> Color.Black
}

fun readerForeground(theme: Int, dark: Boolean = false): Color = when (theme) {
    0 -> Color.Black
    3 -> if (dark) Color.White else Color.Black
    else -> Color.White
}
// ZOUKAN <--
