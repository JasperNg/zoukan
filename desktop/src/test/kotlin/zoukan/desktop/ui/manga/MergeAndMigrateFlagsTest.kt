package zoukan.desktop.ui.manga

import io.kotest.matchers.shouldBe
import mihon.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.Test

class MergeAndMigrateFlagsTest {

    @Test
    fun `the Custom cover checkbox only shows when the entry has a custom cover, as on Android`() {
        migrationDialogFlags(hasCustomCover = false) shouldBe MigrationFlag.entries - MigrationFlag.CUSTOM_COVER
        migrationDialogFlags(hasCustomCover = true) shouldBe MigrationFlag.entries
    }

    @Test
    fun `flags round-trip through the migration_flags preference`() {
        val all = MigrationFlag.entries.toSet()
        MigrationFlag.toBit(emptySet()) shouldBe 0
        MigrationFlag.fromBit(MigrationFlag.toBit(all)) shouldBe all
        // The custom cover bit survives, so a ticked checkbox carries the cover along
        MigrationFlag.fromBit(MigrationFlag.toBit(setOf(MigrationFlag.CUSTOM_COVER))) shouldBe setOf(MigrationFlag.CUSTOM_COVER)
    }
}
