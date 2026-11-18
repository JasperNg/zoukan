package zoukan.desktop.download

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

/**
 * Android's names that are not valid everywhere are looked up on disk only where they can be on
 * disk: on Windows a device name (`NUL`, `com1.txt`) is never a folder, and passing it to the file
 * system would open the device.
 */
class DownloadPathsLegacyNamesTest {

    @Test
    fun `device names are no legacy names on Windows`() {
        listOf("NUL", "con", "Aux.txt", "COM1", "lpt9.cbz", "NUL ").forEach { name ->
            DownloadPaths.legacyNames(name, windows = true) shouldContainExactly emptyList()
        }
    }

    @Test
    fun `device names stay legacy names where Android could write them`() {
        DownloadPaths.legacyNames("NUL", windows = false) shouldContainExactly listOf("NUL")
        DownloadPaths.legacyNames("com1.txt", windows = false) shouldContainExactly listOf("com1.txt")
    }

    @Test
    fun `other names not valid everywhere stay legacy names on Windows`() {
        // A trailing dot: not portable, and no device
        DownloadPaths.legacyNames("Title.", windows = true) shouldContainExactly listOf("Title.")
        // A portable name is never a legacy name
        DownloadPaths.legacyNames("Title", windows = true) shouldContainExactly emptyList()
        DownloadPaths.legacyNames("NUL_", windows = true) shouldContainExactly emptyList()
    }
}
