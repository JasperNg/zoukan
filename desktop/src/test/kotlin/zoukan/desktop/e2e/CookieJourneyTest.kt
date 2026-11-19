package zoukan.desktop.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.secrets.FileCipher
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.desktop.ui.more.SettingsScreen
import kotlin.time.Duration.Companion.minutes

/**
 * A site's session cookie, from start to "Clear cookies": browsing the source gets a cookie from
 * the catalogue (Set-Cookie with Max-Age and Path=/); the next requests send it back exactly once,
 * with its value, and the app keeps it in its cookie file. Clear cookies (Settings > Advanced,
 * found with settings search; it clears the app's cookie store, and Chromium's when it runs, which
 * it does not in tests) removes it: the next request sends no such cookie and the cookie file no
 * longer holds it.
 *
 *   ./gradlew :desktop:journeyTest --tests "*CookieJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class CookieJourneyTest {

    private lateinit var journey: Journey

    @AfterEach
    fun close() {
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun aSiteCookieIsSentOnceAndClearCookiesRemovesIt() {
        journey = Journey.start("cookies")
        val preferences = Injekt.get<DesktopPreferences>()
        var navigator: Navigator? = null
        val cookie = FixtureServer.COOKIE_NAME

        with(journey) {
            val cipher = FileCipher.fromStore(Injekt.get<SecretStore>())
            // The app's cookie file, decrypted; null while there is none
            fun cookieFile(): String? = runCatching {
                val stored = dirs.cookies.readBytes()
                (if (cipher.isEncoded(stored)) cipher.decode(stored) else stored).decodeToString()
            }.getOrNull()

            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                step("Browse ${FixtureSource.NAME}: the catalogue sets a cookie") {
                    onAllNodesWithText("Browse").onFirst().performClick()
                    onAllNodesWithText("Sources").onFirst().performClick()
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodesWithText(FixtureSource.NAME).onFirst().performScrollTo().performClick()
                    waitOrDump("for the catalogue") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
                    // The first request had no cookie to send yet
                    server.requestLog.first { it.path == "/catalog" }.cookieValues(cookie).shouldBeEmpty()
                    shot("1-source")
                }

                step("Opening a title sends the cookie back, once, with its value") {
                    val before = server.requestLog.size
                    onAllNodesWithText(FixtureCatalog.FORMATS_TITLE).onFirst().performClick()
                    waitFor(hasText("Start") and isEnabled())
                    val opened = server.requestLog.drop(before).filter { it.path.startsWith("/manga/") }
                    check(opened.isNotEmpty()) { "opening the title asked the server for nothing: ${server.requestLog.drop(before)}" }
                    opened.forEach { request ->
                        println("Journey $name: ${request.path} sent Cookie ${request.cookieHeaders}")
                        request.cookieValues(cookie) shouldContainExactly listOf(FixtureServer.COOKIE_VALUE)
                    }
                    shot("2-manga")
                }

                step("The cookie is kept in the app's cookie file") {
                    // Saved a moment after it was set
                    waitOrDump("for the cookie in ${dirs.cookies.name}") { cookieFile()?.contains(FixtureServer.COOKIE_VALUE) == true }
                }

                step("Clear cookies in Settings (Advanced), found with settings search") {
                    runOnUiThread { navigator?.push(SettingsScreen) }
                    waitFor(hasText("Search settings"))
                    // Search results jump to the setting's page and highlight it; click the row there to clear cookies
                    onAllNodes(hasSetTextAction()).onFirst().performTextInput("Clear cookies")
                    val clearCookies = hasText("Clear cookies") and !hasSetTextAction()
                    waitFor(clearCookies)
                    shot("3-search")
                    onAllNodes(clearCookies).onFirst().performClick()
                    waitFor(hasText("Signs you out of sites and forgets passed Cloudflare checks"))
                    onAllNodes(clearCookies).onFirst().performScrollTo().performClick()
                    waitOrDump("for the Cookies cleared toast") { "Cookies cleared" in toasts }
                    shot("4-cleared")
                }

                step("The cookie file no longer holds the cookie") {
                    waitOrDump("for the cookie to leave ${dirs.cookies.name}") { cookieFile()?.contains(FixtureServer.COOKIE_VALUE) == false }
                }

                step("The next request sends no cookie") {
                    val before = server.requestLog.size
                    // Back to Browse, and into the source again: it asks for the catalogue
                    runOnUiThread { navigator?.popUntilRoot() }
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodesWithText(FixtureSource.NAME).onFirst().performScrollTo().performClick()
                    waitOrDump("for a request to the server") { server.requestLog.size > before }
                    val next = server.requestLog[before]
                    println("Journey $name: ${next.path} sent Cookie ${next.cookieHeaders}")
                    next.cookieValues(cookie).shouldBeEmpty()
                    waitOrDump("for the catalogue") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == 3 }
                    shot("5-after-clearing")
                }
            }
        }
    }
}
