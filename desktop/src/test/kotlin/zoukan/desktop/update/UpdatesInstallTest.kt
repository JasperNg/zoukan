package zoukan.desktop.update

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.Platform
import java.io.File

class UpdatesInstallTest {
    @Test
    fun `the installer tool is started by its full system path`() {
        val file = File("Zoukan-1.2.0.msi")
        Updates.installerCommand(Platform.WINDOWS, file, systemRoot = "C:\\Windows")!!.first() shouldBe
            File("C:\\Windows", "System32\\msiexec.exe").path
        Updates.installerCommand(Platform.MACOS, file)!!.first() shouldBe "/usr/bin/open"
        Updates.installerCommand(Platform.LINUX, file) shouldBe null
    }
}
