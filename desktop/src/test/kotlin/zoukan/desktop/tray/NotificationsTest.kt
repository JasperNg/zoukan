package zoukan.desktop.tray

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Notifications hide their content with "Hide notification content", and while incognito mode is on. */
class NotificationsTest {

    private val message = Notifications.Message("Downloads finished", "A title: Chapter 3", Notifications.Kind.WARNING)

    @Test
    fun `the content is hidden with the setting, with incognito, or both`() {
        Notifications.hidesContent(hideContentSetting = false, incognito = false) shouldBe false
        Notifications.hidesContent(hideContentSetting = true, incognito = false) shouldBe true
        Notifications.hidesContent(hideContentSetting = false, incognito = true) shouldBe true
        Notifications.hidesContent(hideContentSetting = true, incognito = true) shouldBe true
    }

    @Test
    fun `a hidden notification shows only the placeholder and keeps its kind`() {
        Notifications.shown(message, hideContent = true, placeholder = "Open Zoukan") shouldBe
            Notifications.Message("Zoukan", "Open Zoukan", Notifications.Kind.WARNING)
    }

    @Test
    fun `a shown notification is the message itself`() {
        Notifications.shown(message, hideContent = false, placeholder = "Open Zoukan") shouldBe message
    }
}
