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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.AppLanguage
import zoukan.desktop.ui.common.Corners
import zoukan.desktop.ui.common.Dates
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.colorScheme
import zoukan.desktop.ui.common.pickableThemes
import zoukan.i18n.desktop.DMR
import java.time.LocalDate

/**
 * Settings > Appearance, as the Android app's SettingsAppearanceScreen: theme mode, color scheme,
 * custom color and its style, pure black, date format, relative timestamps and the navigation
 * options. Every setting is the Android one (UiPreferences), so backups carry them both ways.
 */
fun LazyListScope.appearanceSettings() {
    settingsGroup(localized(MR.strings.pref_category_appearance))
    item(key = "appearance-theme") { ThemeSection() }
    item(key = "appearance-display") { DisplaySection() }
    // ZOUKAN -->
    settingsGroup(localized(DMR.strings.entry_page))
    item(key = "appearance-manga-info") { MangaInfoSection() }
    // ZOUKAN <--
    settingsGroup(localized(MR.strings.pref_reader_navigation))
    item(key = "appearance-navigation") { NavigationSection() }
    // ZOUKAN -->
    settingsGroup(localized(DMR.strings.entry_page))
    item(key = "appearance-previews") {
        val ui = remember { Injekt.get<UiPreferences>() }
        SliderPreference(localized(DMR.strings.page_preview_rows), ui.previewsRowCount(), 0..10) { if (it == 0) localized(DMR.strings.hidden) else "$it" }
    }
    // KMK's panorama covers in the feed and global search rows (Android: Appearance > fork settings)
    settingsGroup(localized(MR.strings.browse))
    item(key = "appearance-panorama-cover-flow") {
        val ui = remember { Injekt.get<UiPreferences>() }
        SwitchPreference(localized(KMR.strings.pref_panorama_cover_flow), ui.usePanoramaCoverFlow(), localized(KMR.strings.pref_panorama_cover_flow_summary))
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
        SettingRow(localized(MR.strings.pref_app_theme))
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ThemeMode.SYSTEM to localized(MR.strings.theme_system), ThemeMode.LIGHT to localized(MR.strings.theme_light), ThemeMode.DARK to localized(MR.strings.theme_dark)).forEach { (value, label) ->
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
            localized(DMR.strings.custom_color),
            localized(DMR.strings.the_color_the_custom_theme_is),
            enabled = shownTheme == AppTheme.CUSTOM,
            onClick = { pickingColor = true },
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(seed)))
        }
        ChoiceSetting(
            localized(KMR.strings.pref_custom_theme_style),
            style,
            PaletteStyle.entries.map { it to paletteStyleLabel(it) },
            enabled = shownTheme == AppTheme.CUSTOM,
        ) { ui.customThemeStyle().set(it) }
        SwitchSetting(localized(MR.strings.pref_dark_theme_pure_black), checked = amoled, enabled = mode != ThemeMode.LIGHT) { ui.themeDarkAmoled().set(it) }
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
        SwitchSetting(localized(KMR.strings.pref_theme_cover_based), checked = coverBased) { ui.themeCoverBased().set(it) }
        ChoiceSetting(
            localized(KMR.strings.pref_theme_cover_based_style),
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
                .clip(Corners.Large)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = Corners.Large,
                )
                .background(scheme.background)
                .clickable(onClick = onClick)
                .padding(8.dp),
        ) {
            // A miniature screen: top bar, a cover with badges, and the navigation bar
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(10.dp).clip(Corners.Small).background(scheme.onSurface.copy(alpha = 0.6f)))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.size(width = 44.dp, height = 60.dp).clip(Corners.Medium).background(scheme.onSurface.copy(alpha = 0.15f))) {
                    Row(Modifier.padding(4.dp)) {
                        Box(Modifier.size(width = 10.dp, height = 8.dp).background(scheme.tertiary))
                        Box(Modifier.size(width = 10.dp, height = 8.dp).background(scheme.secondary))
                    }
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(Corners.Medium)
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
        title = { Text(localized(DMR.strings.custom_color)) },
        text = {
            Column(Modifier.width(360.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(Corners.Large).background(color))
                Spacer(Modifier.height(12.dp))
                Text(localized(DMR.strings.hue), style = MaterialTheme.typography.labelMedium)
                Slider(hue, { hue = it }, valueRange = 0f..1f)
                Text(localized(DMR.strings.saturation), style = MaterialTheme.typography.labelMedium)
                Slider(saturation, { saturation = it }, valueRange = 0f..1f)
                Text(localized(DMR.strings.brightness), style = MaterialTheme.typography.labelMedium)
                Slider(value, { value = it }, valueRange = 0f..1f)
                OutlinedTextField(
                    hex,
                    { text ->
                        hex = text.removePrefix("#").take(6).uppercase()
                        hex.toIntOrNull(16)?.takeIf { hex.length == 6 }?.let { setColor(Color(it or (0xFF shl 24))) }
                    },
                    singleLine = true,
                    label = { Text(localized(DMR.strings.hex)) },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(MR.strings.action_cancel)) } },
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
        // ZOUKAN -->
        AppLanguageSetting()
        // ZOUKAN <--
        ChoiceSetting(
            localized(MR.strings.pref_date_format),
            format,
            Dates.formats.map { pattern -> pattern to "${pattern.ifEmpty { "Default" }} (${Dates.format(now, pattern)})" },
        ) { ui.dateFormat().set(it) }
        SwitchPreference(
            localized(MR.strings.pref_relative_format),
            ui.relativeTime(),
            localized(DMR.strings.today_instead_of, Dates.format(now, format)),
        )
    }
}

/**
 * App language, as Android's AppLanguageScreen: the system language, then each language by its own
 * name with its name in the language in use under it. Android redraws the app at once; desktop
 * screens keep the strings they looked up, so the new language shows from the next start.
 */
@Composable
private fun AppLanguageSetting() {
    val preferences = remember { Injekt.get<DesktopPreferences>() }
    val chosen by preferences.appLanguage().changes().collectAsState(preferences.appLanguage().get())
    val languages = remember { AppLanguage.languages(localized(MR.strings.label_default)) }
    var open by remember { mutableStateOf(false) }
    val title = localized(MR.strings.pref_app_language)
    val name = languages.firstOrNull { it.tag == chosen }?.displayName ?: languages.first().displayName
    // The language in use until a restart, when it differs from the chosen one
    val restart = chosen != AppLanguage.current
    SettingRow(title, if (restart) "$name\n${localized(DMR.strings.restart_zoukan_to_use_this_language)}" else name, onClick = { open = true })
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(languages, key = { it.tag }) { language ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                open = false
                                preferences.appLanguage().set(language.tag)
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(language.displayName, style = MaterialTheme.typography.bodyLarge)
                                language.localizedDisplayName?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (language.tag == chosen) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(localized(MR.strings.action_cancel)) } },
        )
    }
}

@Composable
private fun NavigationSection() {
    val ui = remember { Injekt.get<UiPreferences>() }
    Column {
        SwitchPreference(localized(DMR.strings.show_updates_button), ui.showNavUpdates())
        SwitchPreference(localized(DMR.strings.show_history_button), ui.showNavHistory())
        SwitchPreference(localized(DMR.strings.always_show_navigation_labels), ui.bottomBarLabels(), localized(DMR.strings.otherwise_only_the_open_tabs))
    }
}

/** The Android app's English theme names. */
fun themeLabel(theme: AppTheme): String = when (theme) {
    AppTheme.DEFAULT, AppTheme.MONET -> localized(MR.strings.label_default)
    AppTheme.CUSTOM -> localized(DMR.strings.custom_2)
    AppTheme.COTTONCANDY -> localized(DMR.strings.cotton_candy)
    AppTheme.MOCHA -> localized(KMR.strings.theme_mocha)
    AppTheme.CATPPUCCIN -> localized(MR.strings.theme_catppuccin)
    AppTheme.GREEN_APPLE -> localized(MR.strings.theme_greenapple)
    AppTheme.LAVENDER -> localized(MR.strings.theme_lavender)
    AppTheme.MIDNIGHT_DUSK -> localized(MR.strings.theme_midnightdusk)
    AppTheme.NORD -> localized(MR.strings.theme_nord)
    AppTheme.STRAWBERRY_DAIQUIRI -> localized(MR.strings.theme_strawberrydaiquiri)
    AppTheme.TAKO -> localized(MR.strings.theme_tako)
    AppTheme.TEALTURQUOISE -> localized(MR.strings.theme_tealturquoise)
    AppTheme.TIDAL_WAVE -> localized(MR.strings.theme_tidalwave)
    AppTheme.YINYANG -> localized(MR.strings.theme_yinyang)
    AppTheme.YOTSUBA -> localized(MR.strings.theme_yotsuba)
    AppTheme.MONOCHROME -> localized(MR.strings.theme_monochrome)
    AppTheme.CLOUDFLARE -> localized(KMR.strings.theme_cloudflare)
    AppTheme.DOOM -> localized(KMR.strings.theme_doom)
    AppTheme.MATRIX -> localized(KMR.strings.theme_matrix)
    AppTheme.SAPPHIRE -> localized(KMR.strings.theme_sapphire)
    else -> theme.name
}

private fun paletteStyleLabel(style: PaletteStyle): String = when (style) {
    PaletteStyle.TonalSpot -> localized(DMR.strings.tonal_spot)
    PaletteStyle.Neutral -> localized(KMR.strings.pref_theme_cover_based_style_neutral)
    PaletteStyle.Vibrant -> localized(KMR.strings.pref_theme_cover_based_style_vibrant)
    PaletteStyle.Expressive -> localized(KMR.strings.pref_theme_cover_based_style_expressive)
    PaletteStyle.Rainbow -> localized(KMR.strings.pref_theme_cover_based_style_rainbow)
    PaletteStyle.FruitSalad -> localized(DMR.strings.fruit_salad)
    PaletteStyle.Monochrome -> localized(MR.strings.theme_monochrome)
    PaletteStyle.Fidelity -> localized(KMR.strings.pref_theme_cover_based_style_fidelity)
    PaletteStyle.Content -> localized(KMR.strings.pref_theme_cover_based_style_content)
}
