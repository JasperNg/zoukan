// ZOUKAN -->
package eu.kanade.tachiyomi.data.backup

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.backup.create.creators.READING_MODE_MASK
import eu.kanade.tachiyomi.data.backup.restore.restorers.PINNED_SOURCES_PREF_KEY
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The shared `backup` module cannot depend on the app, so it keeps copies of a few app constants.
 * This fails if upstream changes the originals and the copies drift.
 */
class BackupUpstreamConstantsTest {

    @Test
    fun `pinned sources key matches SourcePreferences`() {
        PINNED_SOURCES_PREF_KEY shouldBe SourcePreferences.PINNED_SOURCES_PREF_KEY
    }

    @Test
    fun `reading mode mask matches ReadingMode`() {
        READING_MODE_MASK shouldBe ReadingMode.MASK
    }
}
// ZOUKAN <--
