package zoukan.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.ui.common.Dates
import zoukan.desktop.ui.common.colorScheme
import zoukan.desktop.ui.common.pickableThemes
import java.time.LocalDate

/**
 * Settings > Appearance, as the Android app's SettingsAppearanceScreen: theme mode, color scheme,
 * custom color and its style, pure black, date format, relative timestamps and the navigation
 * options. Every setting is the Android one (UiPreferences), so backups carry them both ways.
 */
fun LazyListScope.appearanceSettings() {
    settingsGroup("Appearance")
    item(key = "appearance-theme") { ThemeSection() }
    item(key = "appearance-display") { DisplaySection() }
    // ZOUKAN -->
    settingsGroup("Entry page")
    item(key = "appearance-manga-info") { MangaInfoSection() }
    // ZOUKAN <--
    settingsGroup("Navigation")
    item(key = "appearance-navigation") { NavigationSection() }
    // ZOUKAN -->
    settingsGroup("Entry page")
    item(key = "appearance-previews") {
        val ui = remember { Injekt.get<UiPreferences>() }
        SliderPreference("Page preview rows", ui.previewsRowCount(), 0..10) { if (it == 0) "Hidden" else "$it" }
    }
    // ZOUKAN <--
}

@Composable
internal fun ThemeSection() {
    val ui = remember { Injekt.get<UiPreferences>() }
    val mode by ui.themeMode().changes().collectAsState(ui.themeMode().get())
    val theme by ui.appTheme().changes().collectAsState(ui.appTheme().get())
    val amoled by ui.themeDarkAmoled().changes().collectAsState(ui.themeDarkAmoled().get())
    val seed by ui.colorTheme().changes().collectAsState(ui.colorTheme().get())
    val style by ui.customThemeStyle().changes().collectAsState(ui.customThemeStyle().get())
    var pickingColor by remember { mutableStateOf(false) }
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    // Android's "Dynamic" is the default there; desktops have no wallpaper colors, so it is "Default"
    val shownTheme = if (theme == AppTheme.MONET) AppTheme.DEFAULT else theme

    Column {
        SettingRow("App theme")
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (value, label) ->
                FilterChip(selected = mode == value, onClick = { ui.themeMode().set(value) }, label = { Text(label) })
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(pickableThemes, key = { it.name }) { appTheme ->
                ThemePreview(
                    appTheme = appTheme,
                    label = themeLabel(appTheme),
                    selected = appTheme == shownTheme,
                    dark = dark,
                    amoled = amoled,
                    seed = seed,
                    style = style,
                    onClick = { ui.appTheme().set(appTheme) },
                )
            }
        }
        SettingRow(
            "Custom color",
            "The color the custom theme is made from",
            enabled = shownTheme == AppTheme.CUSTOM,
            onClick = { pickingColor = true },
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(seed)))
        }
        ChoiceSetting(
            "Custom theme style",
            style,
            PaletteStyle.entries.map { it to paletteStyleLabel(it) },
            enabled = shownTheme == AppTheme.CUSTOM,
        ) { ui.customThemeStyle().set(it) }
        SwitchSetting("Pure black dark mode", checked = amoled, enabled = mode != ThemeMode.LIGHT) { ui.themeDarkAmoled().set(it) }
    }
    if (pickingColor) {
        ColorPickerDialog(
            initial = Color(seed),
            onDismiss = { pickingColor = false },
            onPick = {
                ui.colorTheme().set(it.toArgb())
                pickingColor = false
            },
        )
    }
}

// ZOUKAN -->
/** Android's "Manga info" group: the entry page in its cover's colors, and their style. */
@Composable
private fun MangaInfoSection() {
    val ui = remember { Injekt.get<UiPreferences>() }
    val coverBased by ui.themeCoverBased().changes().collectAsState(ui.themeCoverBased().get())
    val style by ui.themeCoverBasedStyle().changes().collectAsState(ui.themeCoverBasedStyle().get())
    Column {
        SwitchSetting("Theme based on cover", checked = coverBased) { ui.themeCoverBased().set(it) }
        ChoiceSetting(
            "Cover based theme style",
            style,
            PaletteStyle.entries.map { it to paletteStyleLabel(it) },
            enabled = coverBased,
        ) { ui.themeCoverBasedStyle().set(it) }
    }
}
// ZOUKAN <--

@Composable
private fun ThemePreview(
    appTheme: AppTheme,
    label: String,
    selected: Boolean,
    dark: Boolean,
    amoled: Boolean,
    seed: Int,
    style: PaletteStyle,
    onClick: () -> Unit,
) {
    val scheme = remember(appTheme, dark, amoled, seed, style) { colorScheme(appTheme, dark, amoled, seed, style) }
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 96.dp, height = 150.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(16.dp),
                )
                .background(scheme.background)
                .clickable(onClick = onClick)
                .padding(8.dp),
        ) {
            // A miniature screen: top bar, a cover with badges, and the navigation bar
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)).background(scheme.onSurface.copy(alpha = 0.6f)))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.size(width = 44.dp, height = 60.dp).clip(RoundedCornerShape(6.dp)).background(scheme.onSurface.copy(alpha = 0.15f))) {
                    Row(Modifier.padding(4.dp)) {
                        Box(Modifier.size(width = 10.dp, height = 8.dp).background(scheme.tertiary))
                        Box(Modifier.size(width = 10.dp, height = 8.dp).background(scheme.secondary))
                    }
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(scheme.surfaceContainer).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Box(Modifier.size(width = 16.dp, height = 8.dp).clip(CircleShape).background(scheme.secondaryContainer))
                Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.onSurfaceVariant.copy(alpha = 0.6f)))
                Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.onSurfaceVariant.copy(alpha = 0.6f)))
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** A color picker for the custom theme: hue, saturation and value sliders, a hex field and presets. */
@Composable
private fun ColorPickerDialog(initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val hsv = remember {
        FloatArray(3).also { java.awt.Color.RGBtoHSB((initial.red * 255).toInt(), (initial.green * 255).toInt(), (initial.blue * 255).toInt(), it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val color = Color(java.awt.Color.HSBtoRGB(hue, saturation, value) or (0xFF shl 24))
    var hex by remember(color) { mutableStateOf("%06X".format(color.toArgb() and 0xFFFFFF)) }

    fun setColor(c: Color) {
        val out = FloatArray(3)
        java.awt.Color.RGBtoHSB((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), out)
        hue = out[0]
        saturation = out[1]
        value = out[2]
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom color") },
        text = {
            Column(Modifier.width(360.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(color))
                Spacer(Modifier.height(12.dp))
                Text("Hue", style = MaterialTheme.typography.labelMedium)
                Slider(hue, { hue = it }, valueRange = 0f..1f)
                Text("Saturation", style = MaterialTheme.typography.labelMedium)
                Slider(saturation, { saturation = it }, valueRange = 0f..1f)
                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(value, { value = it }, valueRange = 0f..1f)
                OutlinedTextField(
                    hex,
                    { text ->
                        hex = text.removePrefix("#").take(6).uppercase()
                        hex.toIntOrNull(16)?.takeIf { hex.length == 6 }?.let { setColor(Color(it or (0xFF shl 24))) }
                    },
                    singleLine = true,
                    label = { Text("Hex") },
                    prefix = { Text("#") },
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presetColors.forEach { preset ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(preset)).clickable { setColor(Color(preset)) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val presetColors = listOf(
    0xFFDF0090, 0xFFF44336, 0xFFFF9800, 0xFFFFC107, 0xFF8BC34A, 0xFF4CAF50, 0xFF009688,
    0xFF00BCD4, 0xFF2196F3, 0xFF3F51B5, 0xFF673AB7, 0xFF9C27B0, 0xFF795548, 0xFF607D8B,
).map { it.toInt() }

@Composable
private fun DisplaySection() {
    val ui = remember { Injekt.get<UiPreferences>() }
    val now = remember { LocalDate.now() }
    val format by ui.dateFormat().changes().collectAsState(ui.dateFormat().get())
    Column {
        ChoiceSetting(
            "Date format",
            format,
            Dates.formats.map { pattern -> pattern to "${pattern.ifEmpty { "Default" }} (${Dates.format(now, pattern)})" },
        ) { ui.dateFormat().set(it) }
        SwitchPreference(
            "Relative timestamps",
            ui.relativeTime(),
            "\"Today\" instead of \"${Dates.format(now, format)}\"",
        )
    }
}

@Composable
private fun NavigationSection() {
    val ui = remember { Injekt.get<UiPreferences>() }
    Column {
        SwitchPreference("Show Updates button", ui.showNavUpdates())
        SwitchPreference("Show History button", ui.showNavHistory())
        SwitchPreference("Always show navigation labels", ui.bottomBarLabels(), "Otherwise only the open tab's")
    }
}

/** The Android app's English theme names. */
fun themeLabel(theme: AppTheme): String = when (theme) {
    AppTheme.DEFAULT, AppTheme.MONET -> "Default"
    AppTheme.CUSTOM -> "Custom"
    AppTheme.COTTONCANDY -> "Cotton candy"
    AppTheme.MOCHA -> "Mocha"
    AppTheme.CATPPUCCIN -> "Catppuccin"
    AppTheme.GREEN_APPLE -> "Green Apple"
    AppTheme.LAVENDER -> "Lavender"
    AppTheme.MIDNIGHT_DUSK -> "Midnight Dusk"
    AppTheme.NORD -> "Nord"
    AppTheme.STRAWBERRY_DAIQUIRI -> "Strawberry Daiquiri"
    AppTheme.TAKO -> "Tako"
    AppTheme.TEALTURQUOISE -> "Teal & Turquoise"
    AppTheme.TIDAL_WAVE -> "Tidal Wave"
    AppTheme.YINYANG -> "Yin & Yang"
    AppTheme.YOTSUBA -> "Yotsuba"
    AppTheme.MONOCHROME -> "Monochrome"
    AppTheme.CLOUDFLARE -> "Cloudflare"
    AppTheme.DOOM -> "Doom"
    AppTheme.MATRIX -> "Matrix"
    AppTheme.SAPPHIRE -> "Sapphire"
    else -> theme.name
}

private fun paletteStyleLabel(style: PaletteStyle): String = when (style) {
    PaletteStyle.TonalSpot -> "Tonal spot"
    PaletteStyle.Neutral -> "Neutral"
    PaletteStyle.Vibrant -> "Vibrant"
    PaletteStyle.Expressive -> "Expressive"
    PaletteStyle.Rainbow -> "Rainbow"
    PaletteStyle.FruitSalad -> "Fruit salad"
    PaletteStyle.Monochrome -> "Monochrome"
    PaletteStyle.Fidelity -> "Fidelity"
    PaletteStyle.Content -> "Content"
}
