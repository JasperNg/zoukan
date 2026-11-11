package zoukan.extension

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.res.DesktopResources

class DesktopResourcesTest {

    @Test
    fun `display metrics work although android jar constructors are stubs`() {
        val metrics = DesktopResources.INSTANCE.displayMetrics
        metrics.widthPixels shouldBe 1280
        metrics.density shouldBe 1f
    }
}
