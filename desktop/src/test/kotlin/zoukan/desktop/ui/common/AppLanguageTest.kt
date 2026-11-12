package zoukan.desktop.ui.common

import dev.icerock.moko.resources.desc.StringDesc
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.desktop.compat.localizedPlural
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

class AppLanguageTest {
    @Test
    fun `the strings are English on a system in another language, and the region stays`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            AppLanguage.applyEnglish()
            Locale.getDefault() shouldBe Locale.forLanguageTag("en-DE")
            localized(MR.strings.label_default) shouldBe "Default"
            MR.strings.label_default.localized() shouldBe "Default"
            localizedPlural(MR.plurals.notification_update_error, 2, 2) shouldBe "2 updates failed"
            Month.MARCH.getDisplayName(TextStyle.FULL, Locale.getDefault()) shouldBe "March"
        } finally {
            Locale.setDefault(before)
            StringDesc.localeType = StringDesc.LocaleType.System
        }
    }

    @Test
    fun `a script in the system locale is dropped`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("zh-Hant-TW"))
            AppLanguage.applyEnglish()
            Locale.getDefault() shouldBe Locale.forLanguageTag("en-TW")
            localized(MR.strings.label_default) shouldBe "Default"
        } finally {
            Locale.setDefault(before)
            StringDesc.localeType = StringDesc.LocaleType.System
        }
    }
}
