package zoukan.desktop.compat

import android.content.Context
import android.content.res.Resources
import android.widget.Toast
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class ToastCompatTest {

    @Test
    fun `a missing string resource is logged as a warning, not printed`() {
        val resources = mockk<Resources>()
        every { resources.getText(7) } throws Resources.NotFoundException("no string 7")
        val context = mockk<Context>()
        every { context.resources } returns resources

        val logger = LoggerFactory.getLogger(android.util.Log::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val toast = Toast.makeText(context, 7, Toast.LENGTH_SHORT)

            (toast != null) shouldBe true
            val warnings = appender.list.filter { it.level == Level.WARN }
            warnings.size shouldBe 1
            warnings.single().formattedMessage shouldContain "7"
        } finally {
            logger.detachAppender(appender)
        }
    }
}
