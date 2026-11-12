package zoukan.desktop.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.DesktopPreferences

/**
 * Android's tap zones overlay (ReaderNavigationOverlayView): the zones in color with their names.
 * It shows when the reader opens if "Show tap zones overlay" is on (or the first time the reader
 * is used), and whenever the zones change while reading; any click or key hides it.
 */
class TapZonesOverlayState(private val preferences: DesktopPreferences) {
    var visible by mutableStateOf(false)
        private set

    var zones by mutableStateOf<List<ReaderNavigation.Zone>>(emptyList())
        private set

    /** Android's forceNavigationOverlay: read once, then cleared */
    private val newUser = preferences.showNavigationOverlayNewUser().let { pref ->
        pref.get().also { if (it) pref.set(false) }
    }

    private var shown = false

    /** Android's setNavigation: called with the viewer's zones when it starts and when they change. */
    fun onZones(zones: List<ReaderNavigation.Zone>) {
        if (shown && zones == this.zones) return
        val firstLaunch = !shown
        shown = true
        this.zones = zones
        val showOnStart = preferences.showNavigationOverlayOnStart().get() || newUser
        if (visible || (!showOnStart && firstLaunch) || zones.isEmpty()) return
        visible = true
    }

    fun hide() {
        visible = false
    }
}

@Composable
fun TapZonesOverlay(state: TapZonesOverlayState, zones: List<ReaderNavigation.Zone>) {
    LaunchedEffect(zones) { state.onZones(zones) }
    AnimatedVisibility(
        visible = state.visible,
        enter = fadeIn(tween(FADE_MILLIS)),
        exit = fadeOut(tween(FADE_MILLIS)),
        modifier = Modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("tap-zones-overlay")) {
            state.zones.forEach { zone ->
                Box(
                    Modifier
                        .offset(maxWidth * zone.left, maxHeight * zone.top)
                        .size(maxWidth * (zone.right - zone.left), maxHeight * (zone.bottom - zone.top))
                        .background(regionColor(zone.region)),
                    contentAlignment = Alignment.Center,
                ) {
                    val label = regionLabel(zone.region)
                    // White text with a black outline, as Android draws it
                    Text(label, style = labelStyle.copy(color = Color.Black, drawStyle = Stroke(width = 8f)))
                    Text(label, style = labelStyle.copy(color = Color.White))
                }
            }
        }
    }
}

private const val FADE_MILLIS = 1000

private val labelStyle = TextStyle(fontSize = 24.sp, textAlign = TextAlign.Center)

/** Android's NavigationRegion colors */
private fun regionColor(region: ReaderNavigation.Region): Color = when (region) {
    ReaderNavigation.Region.MENU -> Color(0xCC95818D)
    ReaderNavigation.Region.PREV -> Color(0xCCFF7733)
    ReaderNavigation.Region.NEXT -> Color(0xCC84E296)
    ReaderNavigation.Region.LEFT -> Color(0xCC7D1128)
    ReaderNavigation.Region.RIGHT -> Color(0xCCA6CFD5)
}

private fun regionLabel(region: ReaderNavigation.Region): String = when (region) {
    ReaderNavigation.Region.MENU -> localized(MR.strings.action_menu)
    ReaderNavigation.Region.PREV -> localized(MR.strings.nav_zone_prev)
    ReaderNavigation.Region.NEXT -> localized(MR.strings.nav_zone_next)
    ReaderNavigation.Region.LEFT -> localized(MR.strings.nav_zone_left)
    ReaderNavigation.Region.RIGHT -> localized(MR.strings.nav_zone_right)
}
