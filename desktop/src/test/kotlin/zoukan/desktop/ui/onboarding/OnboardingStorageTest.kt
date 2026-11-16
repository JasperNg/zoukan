package zoukan.desktop.ui.onboarding

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OnboardingStorageTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun `checking a storage folder does not create it`() {
        val folder = File(tmp, "not-yet")

        storageFolderReady(folder)

        folder.exists() shouldBe false
    }

    @Test
    fun `an existing writable folder is ready`() {
        storageFolderReady(tmp) shouldBe true
    }

    @Test
    fun `a folder whose parent exists can be created later, so it is ready`() {
        val folder = File(tmp, "new")

        storageFolderReady(folder) shouldBe true

        folder.exists() shouldBe false
    }

    @Test
    fun `a path under a file is not ready`() {
        val file = File(tmp, "f").apply { writeText("x") }

        storageFolderReady(File(file, "x")) shouldBe false
    }
}
