package zoukan.desktop.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CoverCacheClearTest {

    @Test
    fun `clearing the cover cache deletes source covers and keeps custom covers`(@TempDir covers: File) {
        val sourceCovers = (1..3).map { File(covers, "cover$it").apply { writeText("source") } }
        val custom = File(covers, CustomCovers.FOLDER).apply { mkdirs() }
        val customCover = File(custom, "c4ca4238a0b923820dcc509a6f75849b").apply { writeText("mine") }

        CustomCovers.clearCache(covers) shouldBe 3

        sourceCovers.none { it.exists() } shouldBe true
        customCover.readText() shouldBe "mine"
    }

    @Test
    fun `an empty custom covers folder is kept too`(@TempDir covers: File) {
        val custom = File(covers, CustomCovers.FOLDER).apply { mkdirs() }

        CustomCovers.clearCache(covers) shouldBe 0

        custom.isDirectory shouldBe true
    }

    @Test
    fun `a missing covers folder clears nothing`(@TempDir temp: File) {
        CustomCovers.clearCache(File(temp, "none")) shouldBe 0
    }
}
