package zoukan.desktop.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme
import eu.kanade.presentation.theme.colorscheme.CatppuccinColorScheme
import eu.kanade.presentation.theme.colorscheme.CloudflareColorScheme
import eu.kanade.presentation.theme.colorscheme.CottoncandyColorScheme
import eu.kanade.presentation.theme.colorscheme.CustomColorScheme
import eu.kanade.presentation.theme.colorscheme.DoomColorScheme
import eu.kanade.presentation.theme.colorscheme.GreenAppleColorScheme
import eu.kanade.presentation.theme.colorscheme.LavenderColorScheme
import eu.kanade.presentation.theme.colorscheme.MatrixColorScheme
import eu.kanade.presentation.theme.colorscheme.MidnightDuskColorScheme
import eu.kanade.presentation.theme.colorscheme.MochaColorScheme
import eu.kanade.presentation.theme.colorscheme.MonochromeColorScheme
import eu.kanade.presentation.theme.colorscheme.NordColorScheme
import eu.kanade.presentation.theme.colorscheme.SapphireColorScheme
import eu.kanade.presentation.theme.colorscheme.StrawberryColorScheme
import eu.kanade.presentation.theme.colorscheme.TachiyomiColorScheme
import eu.kanade.presentation.theme.colorscheme.TakoColorScheme
import eu.kanade.presentation.theme.colorscheme.TealTurqoiseColorScheme
import eu.kanade.presentation.theme.colorscheme.TidalWaveColorScheme
import eu.kanade.presentation.theme.colorscheme.YinYangColorScheme
import eu.kanade.presentation.theme.colorscheme.YotsubaColorScheme

/**
 * The app's theme, from the Android app's settings (UiPreferences) and color schemes: light, dark
 * or the system's, the color scheme, a custom color and pure black. Android's "Dynamic" (Material
 * You) scheme takes its colors from the phone's wallpaper, which desktops do not offer; it shows
 * the default scheme here.
 */
@Composable
fun ZoukanTheme(preferences: UiPreferences, content: @Composable () -> Unit) {
    val mode by preferences.themeMode().collectAsState()
    val appTheme by preferences.appTheme().collectAsState()
    val amoled by preferences.themeDarkAmoled().collectAsState()
    val seed by preferences.colorTheme().collectAsState()
    val style by preferences.customThemeStyle().collectAsState()
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = remember(appTheme, dark, amoled, seed, style) { colorScheme(appTheme, dark, amoled, seed, style) }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** The color scheme of [appTheme], as the Android app's getThemeColorScheme makes it. */
fun colorScheme(appTheme: AppTheme, dark: Boolean, amoled: Boolean, seed: Int, style: PaletteStyle): ColorScheme {
    val scheme = if (appTheme == AppTheme.CUSTOM) {
        CustomColorScheme(seed = Color(seed), style = style)
    } else {
        colorSchemes[appTheme] ?: TachiyomiColorScheme
    }
    return scheme.getColorScheme(isDark = dark, isAmoled = amoled, overrideDarkSurfaceContainers = true)
}

/** The color schemes the theme picker offers, in the Android app's order (Dynamic is Android only). */
val pickableThemes: List<AppTheme> = AppTheme.entries.filter { it.titleRes != null && it != AppTheme.MONET }

private val colorSchemes: Map<AppTheme, BaseColorScheme> = mapOf(
    AppTheme.DEFAULT to TachiyomiColorScheme,
    AppTheme.CATPPUCCIN to CatppuccinColorScheme,
    AppTheme.GREEN_APPLE to GreenAppleColorScheme,
    AppTheme.LAVENDER to LavenderColorScheme,
    AppTheme.MIDNIGHT_DUSK to MidnightDuskColorScheme,
    AppTheme.MONOCHROME to MonochromeColorScheme,
    AppTheme.NORD to NordColorScheme,
    AppTheme.STRAWBERRY_DAIQUIRI to StrawberryColorScheme,
    AppTheme.TAKO to TakoColorScheme,
    AppTheme.TEALTURQUOISE to TealTurqoiseColorScheme,
    AppTheme.TIDAL_WAVE to TidalWaveColorScheme,
    AppTheme.YINYANG to YinYangColorScheme,
    AppTheme.YOTSUBA to YotsubaColorScheme,
    AppTheme.CLOUDFLARE to CloudflareColorScheme,
    AppTheme.COTTONCANDY to CottoncandyColorScheme,
    AppTheme.DOOM to DoomColorScheme,
    AppTheme.MATRIX to MatrixColorScheme,
    AppTheme.MOCHA to MochaColorScheme,
    AppTheme.SAPPHIRE to SapphireColorScheme,
)
