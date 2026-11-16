package zoukan.desktop.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.security.AppLock
import zoukan.desktop.ui.HomeScreen
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.common.DesktopPreferences
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.settings.SettingsCategory
import zoukan.desktop.ui.settingsPage
import kotlin.time.Duration.Companion.minutes

/**
 * The app lock end to end: a reader sets a password in Settings > Security and the lock covers the
 * app when it comes back from the background, a wrong password is refused, the right one returns
 * to the screen the reader left, and a message shown meanwhile waits for the unlock.
 *
 * The window's focus handlers are not part of `ZoukanContent`, so the test calls
 * `AppLock.onBackground()` and `onForeground()` itself, as the window does.
 *
 *   ./gradlew :desktop:journeyTest --tests "*AppLockJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class AppLockJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun lockAskForThePasswordAndUnlockToTheSameScreen() {
        journey = Journey.start("app-lock")
        val preferences = Injekt.get<DesktopPreferences>()
        val security = Injekt.get<SecurityPreferences>()
        val appLock = Injekt.get<AppLock>()
        var navigator: Navigator? = null
        val lockScreen = hasTestTag("app-lock")
        val field = hasSetTextAction() and hasAnyAncestor(lockScreen)

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                fun ComposeUiTest.submit(password: String) {
                    onAllNodes(field).onFirst().performTextInput(password)
                    onAllNodes(hasText("Unlock") and hasClickAction()).onFirst().performClick()
                }

                setContent { ZoukanContent(preferences) { navigator = it } }
                waitFor(hasText("Library"))

                step("Turn the lock on in Settings > Security and set a password") {
                    appLock.hasPassword() shouldBe false
                    runOnUiThread { HomeScreen.openSettings(navigator!!) }
                    settingsPage(SettingsCategory.Security)
                    onAllNodesWithText("Require unlock").onFirst().performClick()
                    waitFor(hasText("Set a password"))
                    val newPassword = hasSetTextAction() and hasText("New password")
                    val repeat = hasSetTextAction() and hasText("Repeat it")
                    // Saving waits until the secret store was read
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodes(hasText("Save") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                    }
                    onAllNodes(newPassword).onFirst().performTextInput(PASSWORD)
                    onAllNodes(repeat).onFirst().performTextInput("something else")
                    onAllNodes(hasText("Save")).onFirst().performClick()
                    waitFor(hasText("The passwords differ"))
                    security.useAuthenticator().get() shouldBe false
                    onAllNodes(repeat).onFirst().performTextReplacement(PASSWORD)
                    onAllNodes(hasText("Save")).onFirst().performClick()
                    waitOrDump("for the lock to be on") { security.useAuthenticator().get() }
                    appLock.hasPassword() shouldBe true
                    waitOrDump("for the password message") { toasts.contains("Password set") }
                    shot("1-password-set")
                }

                step("Lock after 2 minutes: back in time stays open, after it locks") {
                    val list = settingsPage(SettingsCategory.Security)
                    list.performScrollToNode(hasText("Lock when idle"))
                    onAllNodesWithText("Lock when idle").onFirst().performClick()
                    waitFor(hasText("After 2 minutes"))
                    onAllNodesWithText("After 2 minutes").onFirst().performClick()
                    waitOrDump("for the delay to be saved") { security.lockAppAfter().get() == 2 }

                    val now = System.currentTimeMillis()
                    runOnIdle {
                        appLock.onBackground()
                        appLock.onForeground(now = now + 60_000)
                    }
                    waitForIdle()
                    appLock.locked.value shouldBe false
                    onAllNodes(lockScreen).fetchSemanticsNodes().size shouldBe 0

                    runOnIdle {
                        appLock.onBackground()
                        appLock.onForeground(now = now + 3 * 60_000)
                    }
                    waitFor(lockScreen)
                    appLock.locked.value shouldBe true
                    submit(PASSWORD)
                    waitOrDump("for the unlock") { onAllNodes(lockScreen).fetchSemanticsNodes().isEmpty() }
                    appLock.locked.value shouldBe false
                    waitFor(hasText("Lock when idle"))

                    // Back to "Always" through the same row
                    list.performScrollToNode(hasText("Lock when idle"))
                    onAllNodesWithText("Lock when idle").onFirst().performClick()
                    waitFor(hasText("Always"))
                    onAllNodesWithText("Always").onFirst().performClick()
                    waitOrDump("for the delay to read Always") { security.lockAppAfter().get() == 0 }
                }

                step("Go to Statistics, then lock") {
                    runOnUiThread { navigator!!.popUntilRoot() }
                    waitFor(hasText("More"))
                    onAllNodesWithText("More").onFirst().performClick()
                    waitFor(hasText("Statistics"))
                    onAllNodesWithText("Statistics").onFirst().performClick()
                    // The title of the open screen; the More list is gone
                    waitOrDump("for the Statistics screen") { onAllNodesWithText("Statistics").fetchSemanticsNodes().isNotEmpty() && onAllNodesWithText("More").fetchSemanticsNodes().isEmpty() }
                    runOnIdle {
                        appLock.onBackground()
                        appLock.onForeground()
                    }
                    waitFor(lockScreen)
                    appLock.locked.value shouldBe true
                    waitFor(hasText("Zoukan is locked"))
                    onAllNodesWithText("Statistics").fetchSemanticsNodes().size shouldBe 0
                    onAllNodesWithText("Library").fetchSemanticsNodes().size shouldBe 0
                    shot("2-locked")
                }

                step("A message shown while locked waits for the unlock") {
                    Toasts.show(MESSAGE)
                    waitOrDump("for the message to be sent") { toasts.contains(MESSAGE) }
                    waitForIdle()
                    onAllNodesWithText(MESSAGE).fetchSemanticsNodes().size shouldBe 0
                }

                step("A wrong password is refused and the lock stays") {
                    submit("not the password")
                    waitFor(hasText("Wrong password"))
                    onAllNodes(lockScreen).fetchSemanticsNodes().size shouldBe 1
                    appLock.locked.value shouldBe true
                    onAllNodesWithText(MESSAGE).fetchSemanticsNodes().size shouldBe 0
                    shot("3-wrong-password")
                }

                step("The right password unlocks to the same screen and shows the message") {
                    submit(PASSWORD)
                    waitOrDump("for the unlock") { onAllNodes(lockScreen).fetchSemanticsNodes().isEmpty() }
                    appLock.locked.value shouldBe false
                    waitFor(hasText("Statistics"))
                    waitFor(hasText(MESSAGE))
                    shot("4-unlocked")
                }
            }
        }
    }

    private companion object {
        const val PASSWORD = "journey-pass"
        const val MESSAGE = "journey-message"
    }
}
