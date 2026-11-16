package zoukan.desktop.packaging

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class EntitlementsTest {
    private val allowed =
        setOf(
            "com.apple.security.cs.allow-jit",
            "com.apple.security.cs.allow-unsigned-executable-memory",
            "com.apple.security.cs.disable-library-validation",
        )

    @Test
    fun `the macOS entitlements grant only what the JVM needs`() {
        val keys =
            Regex("<key>([^<]+)</key>")
                .findAll(File("packaging/macos/entitlements.plist").readText())
                .map { it.groupValues[1] }
                .toSet()
        keys shouldBe allowed
    }

    @Test
    fun `there is a single entitlements file`() {
        File("packaging/macos")
            .listFiles { f -> f.name.endsWith(".plist") }!!
            .map { it.name } shouldBe listOf("entitlements.plist")
    }

    @Test
    fun `the Windows install check fails a broken signature`() {
        File("packaging/check/install-check-windows.ps1").readText().contains("'UnknownError'") shouldBe false
    }
}
