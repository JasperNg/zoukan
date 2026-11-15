package zoukan.desktop.ui.reader

import eu.kanade.domain.source.service.SourcePreferences
import exh.source.EH_SOURCE_ID
import exh.source.ExhPreferences
import exh.source.MERGED_SOURCE_ID
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import zoukan.desktop.security.Incognito
import zoukan.extension.DesktopExtensionManager

/**
 * Per-extension incognito in a merged entry follows each chapter's own source, not the merged
 * entry's (which no extension has), as the reader resolves it with [chapterManga].
 */
class ReaderIncognitoTest {
    private fun <T> pref(value: T): Preference<T> = mockk { every { get() } returns value }

    // E-Hentai's "Incognito" on: its galleries are incognito, other sources are not
    private val incognito = Incognito(
        preferenceStore = mockk<PreferenceStore> { every { getBoolean(any(), any()) } returns pref(false) },
        sourcePreferences = mockk<SourcePreferences> { every { incognitoExtensions() } returns pref(emptySet()) },
        extensionManager = mockk<DesktopExtensionManager> { every { installedExtensionsFlow } returns MutableStateFlow(emptyMap()) },
        exhPreferences = mockk<ExhPreferences> { every { ehIncognitoMode() } returns pref(true) },
    )

    private val merged = Manga.create().copy(id = 1, source = MERGED_SOURCE_ID)
    private val gallery = Manga.create().copy(id = 2, source = EH_SOURCE_ID)
    private val other = Manga.create().copy(id = 3, source = 12345L)
    private val entries = listOf(merged, gallery, other).associateBy { it.id }

    private fun chapterOf(manga: Manga) = Chapter.create().copy(id = manga.id * 10, mangaId = manga.id)

    private fun incognitoFor(readerManga: Manga, chapter: Chapter): Boolean = runBlocking {
        val manga = chapterManga(readerManga, chapter) { entries[it] }!!
        incognito.isOn(manga.source)
    }

    @Test
    fun `a merged entry's chapter from an incognito source is read in incognito`() {
        // The merged entry's own source is no extension's, so it alone would keep history
        incognito.isOn(merged.source) shouldBe false
        incognitoFor(merged, chapterOf(gallery)) shouldBe true
    }

    @Test
    fun `a merged entry's chapter from another source keeps its history`() {
        incognitoFor(merged, chapterOf(other)) shouldBe false
    }

    @Test
    fun `an ordinary entry uses its own source and is not looked up again`() = runBlocking<Unit> {
        chapterManga(gallery, chapterOf(gallery)) { error("not looked up") } shouldBe gallery
        incognitoFor(gallery, chapterOf(gallery)) shouldBe true
    }

    @Test
    fun `a merged entry whose part is gone resolves to nothing`() = runBlocking<Unit> {
        chapterManga(merged, Chapter.create().copy(id = 99, mangaId = 42)) { null } shouldBe null
    }
}
