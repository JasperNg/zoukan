package zoukan.desktop.exh

import android.app.Application
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.source.online.all.MergedSource
import exh.source.EHENTAI_EXT_SOURCES
import exh.source.EXHENTAI_EXT_SOURCES
import exh.source.ExhPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * The Android app's built-in sources that the desktop shares: merged manga always, E-Hentai
 * when "E-Hentai" is on in Settings, ExHentai as well once signed in. As AndroidSourceManager.
 */
class BuiltInSources(private val context: Application, private val exhPreferences: ExhPreferences) {
    val merged: MergedSource by lazy { MergedSource() }

    /** Created once per id, so a settings change does not lose a source's state. */
    private val ehentai = mutableMapOf<Long, EHentai>()

    private fun ehentai(id: Long, exh: Boolean, lang: String): EHentai = synchronized(ehentai) {
        ehentai.getOrPut(id) { EHentai(id, exh, context, lang) }
    }

    /** Sources an end-to-end test adds, to search and migrate offline */
    val testSources = MutableStateFlow<List<Source>>(emptyList())

    fun optional(): Flow<List<Source>> = combine(
        exhPreferences.isHentaiEnabled().changes(),
        exhPreferences.enableExhentai().changes(),
        testSources,
    ) { hentai, exhentai, tests ->
        buildList {
            add(merged)
            addAll(tests)
            if (hentai) {
                EHENTAI_EXT_SOURCES.forEach { (id, lang) -> add(ehentai(id, false, lang)) }
                if (exhentai) EXHENTAI_EXT_SOURCES.forEach { (id, lang) -> add(ehentai(id, true, lang)) }
            }
        }
    }
}
