package zoukan.desktop

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.app.SingleInstance
import java.io.File

class HarnessProfileTest {
    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        System.setProperty("zoukan.dataDir", File(tempDir, "data").path)
    }

    @AfterEach
    fun tearDown() {
        SingleInstance.closeAll()
        System.clearProperty("zoukan.dataDir")
    }

    @Test
    fun `the harness profile is outside the user's data folder`() {
        val data = ZoukanDirs.default().root
        harnessDirs().root.toPath().startsWith(data.toPath()) shouldBe false
        (harnessDirs().root == data) shouldBe false
    }

    @Test
    fun `a second harness run on the same profile is refused`() {
        val held = SingleInstance.acquireForCommand(harnessDirs().root)
        (held != null) shouldBe true
        lockHarness(harnessDirs()) shouldBe null
    }

    @Test
    fun `a harness run can start again once the first one has finished`() {
        val first = lockHarness(harnessDirs())
        (first != null) shouldBe true
        first?.close()
        val second = lockHarness(harnessDirs())
        (second != null) shouldBe true
        second?.close()
    }
}
