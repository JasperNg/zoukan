package zoukan.desktop.ui.common

import dev.icerock.moko.resources.desc.StringDesc
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.compat.localizedPlural
import java.io.File
import java.util.Locale

class AppLanguageTest {
    @Test
    fun `the languages are Android's locales_config`() {
        // As LocalesConfigTask: each folder with a non-empty strings.xml
        val empty = "<resources>\\s*</resources>|<resources\\s*/>".toRegex()
        val folders = File("../i18n/src/commonMain/moko-resources").listFiles()!!
            .filter { File(it, "strings.xml").let { strings -> strings.isFile && !strings.readText().contains(empty) } }
            .map { it.name.replace("base", "en").replace("-r", "-").replace("+", "-") }
        AppLanguage.tags.sorted() shouldBe folders.sorted()
    }

    @Test
    fun `the system language comes first, then the languages by their own names`() {
        val languages = AppLanguage.languages("Default")
        languages.first() shouldBe AppLanguage.Language("", "Default", null)
        val rest = languages.drop(1)
        rest.map { it.displayName } shouldBe rest.map { it.displayName }.sorted()
        rest.first { it.tag == "de" }.displayName shouldBe "Deutsch"
        rest.first { it.tag == "zh-TW" }.displayName shouldBe Locale.forLanguageTag("zh-Hant").let { it.getDisplayName(it) }
    }

    @Test
    fun `a chosen language changes the strings and the system language brings them back`() {
        val before = Locale.getDefault()
        try {
            AppLanguage.apply("de")
            AppLanguage.current shouldBe "de"
            localized(MR.strings.label_default) shouldBe "Standard"
            AppLanguage.apply("en")
            localized(MR.strings.label_default) shouldBe "Default"
            localizedPlural(MR.plurals.notification_update_error, 2, 2) shouldBe "2 updates failed"
            // An unknown tag (an older or edited preference) is the system language
            AppLanguage.apply("xx")
            AppLanguage.current shouldBe ""
            Locale.getDefault() shouldBe before
        } finally {
            Locale.setDefault(before)
            StringDesc.localeType = StringDesc.LocaleType.System
        }
    }
}
