package zoukan.desktop.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR
import java.io.IOException

class DownloadSpaceTest {

    @Test
    fun `a disk that reports no free space is full`() {
        val min = DownloadManager.MIN_FREE_SPACE
        DownloadManager.hasRoom(0L) shouldBe false
        DownloadManager.hasRoom(min - 1) shouldBe false
        DownloadManager.hasRoom(min) shouldBe true
        DownloadManager.hasRoom(null) shouldBe true
    }

    @Test
    fun `a full disk error reads as not enough space`() {
        val full = localized(DMR.strings.download_disk_full)
        DownloadManager.errorText(IOException("No space left on device")) shouldBe full
        DownloadManager.errorText(IOException("There is not enough space on the disk")) shouldBe full
        DownloadManager.errorText(RuntimeException("wrapped", IOException("No space left on device"))) shouldBe full
        DownloadManager.errorText(IOException("HTTP 500")) shouldBe "HTTP 500"
    }
}
