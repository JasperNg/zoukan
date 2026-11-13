package zoukan.desktop.ui.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Two downloads of one cover each write their own temporary file (ui.md, Images.kt:109). */
class CoverFileWriteTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `two writes at once never mix their bytes and leave no temporary files`() {
        val target = File(dir, "cover")
        val bothWriting = CountDownLatch(2)
        val writers = listOf('a', 'b').map { c ->
            thread {
                Images.writeCoverFile(target) { out ->
                    out.write(ByteArray(50_000) { c.code.toByte() })
                    bothWriting.countDown()
                    bothWriting.await()
                    out.write(ByteArray(50_000) { c.code.toByte() })
                }
            }
        }
        writers.forEach { it.join() }
        val bytes = target.readBytes()
        bytes.size shouldBe 100_000
        bytes.toSet().size shouldBe 1
        dir.list()!!.toList() shouldBe listOf("cover")
    }

    @Test
    fun `a failed write keeps no partial cover`() {
        val target = File(dir, "cover")
        runCatching { Images.writeCoverFile(target) { error("network dropped") } }
        target.exists() shouldBe false
        dir.list()!!.toList() shouldBe emptyList()
    }
}
