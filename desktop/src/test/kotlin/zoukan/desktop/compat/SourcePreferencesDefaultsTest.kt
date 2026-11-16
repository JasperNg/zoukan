package zoukan.desktop.compat

import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.app.DesktopPreferences
import java.io.File
import java.util.Locale

/** The source settings have one declaration each, and the default languages include the system language. */
class SourcePreferencesDefaultsTest {

    @TempDir
    lateinit var root: File

    private fun store(): DesktopPreferenceStore {
        System.setProperty("$CONFIG_PREFIX.server.rootDir", root.absolutePath)
        return DesktopPreferenceStore(JavaSharedPreferences("source_defaults_test"))
    }

    private fun withLocale(locale: Locale, block: () -> Unit) {
        val saved = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            block()
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `the default languages include the system language`() = withLocale(Locale.FRENCH) {
        val languages = SourcePreferences(store()).enabledLanguages().get()
        languages shouldContain "fr"
        languages shouldContain "all"
        languages shouldContain "en"
    }

    @Test
    fun `both preference classes agree on every shared key`() = withLocale(Locale.FRENCH) {
        val store = store()
        val source = SourcePreferences(store)
        val desktop = DesktopPreferences(store)

        desktop.enabledLanguages().key() shouldBe source.enabledLanguages().key()
        desktop.enabledLanguages().defaultValue() shouldBe source.enabledLanguages().defaultValue()
        desktop.showNsfwSource().key() shouldBe source.showNsfwSource().key()
        desktop.showNsfwSource().defaultValue() shouldBe source.showNsfwSource().defaultValue()
        desktop.pinnedSources().key() shouldBe source.pinnedSources().key()
        desktop.pinnedSources().defaultValue() shouldBe source.pinnedSources().defaultValue()
    }
}
