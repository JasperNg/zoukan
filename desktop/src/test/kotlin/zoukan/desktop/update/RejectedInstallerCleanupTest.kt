package zoukan.desktop.update

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import zoukan.desktop.testing.FileSystemSandbox
import java.io.File

/**
 * A rejected installer's private copy sits in a `run*` folder that is not private on Windows:
 * another program may have put a link or a junction in it by the time it is deleted
 * ([Updates.discardRejected]).
 */
class RejectedInstallerCleanupTest {
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun setUp(sandbox: FileSystemSandbox): Triple<File, File, File> {
        val updates = File(sandbox.dataFolder, "updates").apply { mkdirs() }
        val installer = File(updates, "Zoukan-2.0.0.msi").apply { writeText("msi") }
        val sig = File(updates, "Zoukan-2.0.0.msi.sig").apply { writeText("sig") }
        val run = File(updates, "run123").apply { mkdirs() }
        File(run, installer.name).writeText("copy")
        return Triple(run, installer, sig)
    }

    @Test
    fun `a link put in the run folder is removed as itself`() {
        FileSystemSandbox.create().use { sandbox ->
            val (run, installer, sig) = setUp(sandbox)
            val outside = File(sandbox.userFolder, "documents").apply { mkdirs() }
            File(outside, "letter.txt").writeText("the user's")
            assumeTrue(sandbox.link(File(run, "swapped"), outside) != null, "symbolic links need rights on this system")
            sandbox.record()

            Updates.discardRejected(run.toPath(), installer, sig)

            run.exists() shouldBe false
            installer.exists() shouldBe false
            sig.exists() shouldBe false
            File(outside, "letter.txt").readText() shouldBe "the user's"
            sandbox.assertNothingOutsideAppChanged()
        }
    }

    /** Not run on macOS or Linux: junctions are Windows only. */
    @Test
    fun `a Windows junction put in the run folder is removed as itself`() {
        assumeTrue(isWindows, "junctions are Windows only; the symbolic link test covers other systems")
        FileSystemSandbox.create().use { sandbox ->
            val (run, installer, sig) = setUp(sandbox)
            val outside = File(sandbox.userFolder, "documents").apply { mkdirs() }
            File(outside, "letter.txt").writeText("the user's")
            val junction = File(run, "swapped")
            val made = ProcessBuilder("cmd", "/c", "mklink", "/J", junction.absolutePath, outside.absolutePath)
                .redirectErrorStream(true).start().waitFor()
            assumeTrue(made == 0 && junction.exists(), "mklink /J failed")
            sandbox.record()

            Updates.discardRejected(run.toPath(), installer, sig)

            run.exists() shouldBe false
            File(outside, "letter.txt").readText() shouldBe "the user's"
            sandbox.assertNothingOutsideAppChanged()
        }
    }
}
