package zoukan.desktop

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import zoukan.desktop.secrets.SecretStore
import java.nio.file.Files

/** Starts the runtime, so it runs in its own JVM under wiringTest. */
class ZoukanRuntimeStartTest {
    @Test
    fun `a start that failed fails again instead of returning a half-built runtime`() {
        System.setProperty(SecretStore.FILE_STORE_PROPERTY, "true")
        System.setProperty("zoukan.noCrashDialog", "true")
        // A data folder that is a file: the folders cannot be made and server.conf cannot be written
        val root = Files.createTempFile("zoukan-not-a-folder", "").toFile()
        val dirs = ZoukanDirs(root)
        val first = shouldThrowAny { ZoukanRuntime.start(dirs, interactiveBrowser = false) }
        val second = shouldThrow<IllegalStateException> { ZoukanRuntime.start(dirs, interactiveBrowser = false) }
        second.cause shouldBeSameInstanceAs first
    }
}
