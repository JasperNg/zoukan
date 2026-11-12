package zoukan.desktop.ui.common

import dev.icerock.moko.resources.desc.StringDesc
import java.util.Locale

/**
 * Settings > Appearance > App language, as Android's AppLanguageScreen. Android keeps the choice in
 * AppCompatDelegate's application locales, outside the preferences; the desktop keeps it in an app
 * state preference (DesktopPreferences.appLanguage), so it stays out of backups there too.
 */
object AppLanguage {
    /**
     * The languages the Android app offers: its locales_config, made by LocalesConfigTask from the
     * i18n module's folders with a non-empty strings.xml ("base" is "en", "-r" becomes "-").
     * AppLanguageTest checks this against the folders.
     */
    val tags = listOf(
        "am", "ar", "as", "be", "bg", "bn", "ca", "ceb", "cs", "cv", "da", "de", "el", "en", "eo", "es", "eu",
        "fa", "fi", "fil", "fr", "gl", "he", "hi", "hr", "hu", "in", "it", "ja", "jv", "ka-GE", "kk", "km",
        "kmr", "kn", "ko", "lt", "lv", "ml", "mr", "ms", "my", "nb-NO", "ne", "nl", "nn", "pl", "pt",
        "pt-BR", "ro", "ru", "sa", "sah", "sc", "sdh", "sk", "sq", "sr", "sv", "ta", "te", "th", "tr", "uk",
        "uz", "vi", "zh-CN", "zh-TW",
    )

    data class Language(
        /** Empty for the system language */
        val tag: String,
        /** The language's name in that language, as LocaleHelper.getLocalizedDisplayName */
        val displayName: String,
        /** The name in the language Zoukan shows now, as LocaleHelper.getDisplayName; null for the system language */
        val localizedDisplayName: String?,
    )

    /** The system language, from before Zoukan set its own */
    private val system: Locale = Locale.getDefault()

    /** The language in use, or "" for the system language */
    @Volatile
    var current: String = ""
        private set

    /** Shows Zoukan in [tag] ("" is the system language), from the next string it looks up. */
    fun apply(tag: String) {
        val known = tag.takeIf { it in tags }.orEmpty()
        current = known
        val locale = if (known.isEmpty()) system else Locale.forLanguageTag(known)
        // The default locale too, not only moko's: with a language that has no bundle of its own
        // (English is the base bundle), ResourceBundle falls back to the default locale first
        Locale.setDefault(locale)
        StringDesc.localeType = if (known.isEmpty()) StringDesc.LocaleType.System else StringDesc.LocaleType.Custom(locale.toLanguageTag())
    }

    /** The system language first, then the languages by their own names, as Android's getLangs. */
    fun languages(systemLabel: String): List<Language> =
        listOf(Language("", systemLabel, null)) +
            tags.map { Language(it, localizedDisplayName(it), displayName(it)) }
                .filter { it.displayName.isNotEmpty() }
                .sortedBy { it.displayName }

    /** LocaleHelper.getLocalizedDisplayName: the name in the language itself, capitalized */
    fun localizedDisplayName(tag: String): String {
        val locale = displayLocale(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.uppercase(locale) }
    }

    /** LocaleHelper.getDisplayName: the name in the language in use */
    fun displayName(tag: String): String = displayLocale(tag).displayName

    /** As LocaleHelper: Chinese is named by its script */
    private fun displayLocale(tag: String): Locale = when (tag) {
        "zh-CN" -> Locale.forLanguageTag("zh-Hans")
        "zh-TW" -> Locale.forLanguageTag("zh-Hant")
        else -> Locale.forLanguageTag(tag)
    }
}
