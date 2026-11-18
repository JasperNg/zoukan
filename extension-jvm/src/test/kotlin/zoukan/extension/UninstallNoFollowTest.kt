package zoukan.extension

import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/** Uninstalling deletes the package's folder and never what a link or a junction in it points to. */
class UninstallNoFollowTest {
    private val pkgName = "eu.kanade.tachiyomi.extension.en.example"

    private fun installer(extensionsDir: File) = DesktopExtensionInstaller(extensionsDir) { OkHttpClient() }

    @Test
    fun `a link inside the package folder is removed as itself`(@TempDir temp: File) {
        val outside = File(temp, "documents").apply { mkdirs() }
        File(outside, "letter.txt").writeText("the user's")
        val extensionsDir = File(temp, "extensions")
        val packageDir = File(extensionsDir, pkgName).apply { mkdirs() }
        File(packageDir, "3.jar").writeText("jar")
        val linked = runCatching { Files.createSymbolicLink(File(packageDir, "linked").toPath(), outside.toPath()) }.isSuccess
        assumeTrue(linked, "symbolic links need rights on this system")

        installer(extensionsDir).uninstall(pkgName)

        packageDir.exists() shouldBe false
        File(outside, "letter.txt").readText() shouldBe "the user's"
    }

    /**
     * A package folder that is itself a junction: the canonical path may not resolve a junction on
     * Windows, so the check that it sits in the extensions folder can pass. Not run on macOS or
     * Linux: junctions are Windows only.
     */
    @Test
    fun `a package folder that is a Windows junction is removed as itself`(@TempDir temp: File) {
        assumeTrue(System.getProperty("os.name").orEmpty().lowercase().contains("win"), "junctions are Windows only")
        val outside = File(temp, "documents").apply { mkdirs() }
        File(outside, "letter.txt").writeText("the user's")
        val extensionsDir = File(temp, "extensions").apply { mkdirs() }
        val junction = File(extensionsDir, pkgName)
        val made = ProcessBuilder("cmd", "/c", "mklink", "/J", junction.absolutePath, outside.absolutePath)
            .redirectErrorStream(true).start().waitFor()
        assumeTrue(made == 0 && junction.exists(), "mklink /J failed")

        // Refusing it (a canonical path that resolves the junction) is as good as removing the junction
        runCatching { installer(extensionsDir).uninstall(pkgName) }

        File(outside, "letter.txt").readText() shouldBe "the user's"
    }
}
