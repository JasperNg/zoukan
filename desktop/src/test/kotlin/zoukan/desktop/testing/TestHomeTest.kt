package zoukan.desktop.testing

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import zoukan.desktop.ZoukanDirs
import java.io.File

class TestHomeTest {
    /** The project's build folder, where the build puts the test JVMs' home and data folders. */
    private val testHomes = File(System.getProperty("user.dir"), "build/test-home").absoluteFile

    @Test
    fun `a test JVM the build starts has a home and a data folder under the build folder`() {
        // Gradle names its test workers; a run from an IDE may not have the build's properties
        assumeTrue(System.getProperty("org.gradle.test.worker") != null, "not started by Gradle")
        TestHome.isSandboxed() shouldBe true
        TestHome.root().startsWith(testHomes) shouldBe true
        ZoukanDirs.default().root.startsWith(testHomes) shouldBe true
        ZoukanDirs.harness().root.startsWith(testHomes) shouldBe true
    }

    @Test
    fun `the home folder is never the real one, whatever the properties say`() {
        TestHome.withSystemProperty(TestHome.MARKER, null) {
            TestHome.isSandboxed() shouldBe false
            TestHome.root() shouldBe File(System.getProperty("java.io.tmpdir"), "zoukan-test-home").absoluteFile
        }
    }

    @Test
    fun `a property set for one block gets its old value back, or none`() {
        val key = "zoukan.test.property"
        TestHome.withSystemProperty(key, "outer") {
            TestHome.withSystemProperty(key, "inner") { System.getProperty(key) shouldBe "inner" }
            System.getProperty(key) shouldBe "outer"
            TestHome.withSystemProperty(key, null) { System.getProperty(key) shouldBe null }
            System.getProperty(key) shouldBe "outer"
        }
        System.getProperty(key) shouldBe null
    }
}
