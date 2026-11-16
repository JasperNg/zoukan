package zoukan.desktop.backup

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.runCommand
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.security.MessageDigest
import kotlin.random.Random

/** `backup synthetic` never replaces an existing file unless asked, and never through a half-written file. */
class BackupCommandTest {

    @TempDir
    lateinit var dir: File

    private fun run(vararg args: String): Pair<Int, String> {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true))
        try {
            return runCommand(arrayOf(*args)) to captured.toString()
        } finally {
            System.setErr(original)
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()

    private fun existingBackup(): File = File(dir, "real.tachibk").apply { writeBytes(Random(7).nextBytes(64 * 1024)) }

    private fun decodes(file: File) {
        DesktopBackups(File("."), stubSourceRepository = NoStubSources).read(file)
    }

    @Test
    fun `synthetic refuses an existing file without force`() {
        val file = existingBackup()
        val original = file.readBytes()
        val modified = file.lastModified()

        val (code, err) = run("backup", "synthetic", file.path)

        code shouldBe 1
        err shouldContain file.path
        err shouldContain "--force"
        file.readBytes().contentEquals(original) shouldBe true
        sha256(file.readBytes()) shouldBe sha256(original)
        file.length() shouldBe original.size.toLong()
        file.lastModified() shouldBe modified
        File(dir, "real.tachibk.tmp").exists() shouldBe false
    }

    @Test
    fun `synthetic with force replaces the file through a temporary file`() {
        val file = existingBackup()

        val (code, _) = run("backup", "synthetic", file.path, "--force")

        code shouldBe 0
        decodes(file)
        File(dir, "real.tachibk.tmp").exists() shouldBe false
    }

    @Test
    fun `synthetic writes a new file`() {
        val file = File(dir, "new.tachibk")

        val (code, _) = run("backup", "synthetic", file.path)

        code shouldBe 0
        decodes(file)
        File(dir, "new.tachibk.tmp").exists() shouldBe false
    }

    @Test
    fun `synthetic leaves a file named like its temporary file alone`() {
        val bystander = File(dir, "new.tachibk.tmp").apply { writeBytes(Random(11).nextBytes(4 * 1024)) }
        val original = bystander.readBytes()

        val (code, _) = run("backup", "synthetic", File(dir, "new.tachibk").path)

        code shouldBe 0
        bystander.exists() shouldBe true
        sha256(bystander.readBytes()) shouldBe sha256(original)
        dir.list()!!.sorted() shouldBe listOf("new.tachibk", "new.tachibk.tmp")
    }
}
