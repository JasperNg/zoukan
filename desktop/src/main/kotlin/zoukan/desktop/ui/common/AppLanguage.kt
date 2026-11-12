package zoukan.desktop.ui.common

import dev.icerock.moko.resources.desc.StringDesc
import java.util.Locale

/**
 * Zoukan is English only. Android offers an app language setting; the desktop has none, and shows
 * English whatever the system language is.
 */
object AppLanguage {
    /**
     * Shows Zoukan in English, before anything looks up a string. The default locale becomes English
     * with the system's region (as en-DE), so moko strings and the month and day names are English
     * while the region still sets the first day of the week and number formats.
     */
    fun applyEnglish() {
        val system = Locale.getDefault()
        val english = runCatching { Locale.Builder().setLocale(system).setLanguage("en").setScript("").build() }
            .getOrDefault(Locale.ENGLISH)
        Locale.setDefault(english)
        StringDesc.localeType = StringDesc.LocaleType.Custom("en")
    }
}
