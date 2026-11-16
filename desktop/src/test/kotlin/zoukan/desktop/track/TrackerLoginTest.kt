package zoukan.desktop.track

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class TrackerLoginTest {
    @Test
    fun `upper-case host is handled the same as lower-case`() {
        val redirect = TrackerLogin.parseRedirect("KOMIKKU://AniList-Auth#access_token=t")!!
        redirect.host shouldBe "anilist-auth"
        TrackerLogin.decide(redirect, expectedHost = "anilist-auth") shouldBe TrackerLogin.RedirectAction.SIGN_IN
    }

    @Test
    fun `error message does not contain the code`() {
        val message = shouldThrow<IllegalStateException> {
            TrackerLogin.requireRedirect("komikku://other-auth?code=SECRETCODE")
        }.message.orEmpty()
        message shouldNotContain "SECRETCODE"
    }

    @Test
    fun `the AniList window ignores a mangadex-auth redirect`() {
        val redirect = TrackerLogin.parseRedirect("komikku://mangadex-auth?code=attacker")!!
        TrackerLogin.decide(redirect, expectedHost = "anilist-auth") shouldBe TrackerLogin.RedirectAction.IGNORE
    }

    @Test
    fun `a redirect without a code does not sign out`() {
        val redirect = TrackerLogin.parseRedirect("komikku://shikimori-auth")!!
        TrackerLogin.decide(redirect, expectedHost = "shikimori-auth") shouldBe TrackerLogin.RedirectAction.IGNORE
    }

    @Test
    fun `the tracker's own redirect with a code or an error is acted on`() {
        TrackerLogin.decide(TrackerLogin.parseRedirect("komikku://anilist-auth#access_token=t")!!, "anilist-auth") shouldBe
            TrackerLogin.RedirectAction.SIGN_IN
        TrackerLogin.decide(TrackerLogin.parseRedirect("komikku://myanimelist-auth?code=c")!!, "myanimelist-auth") shouldBe
            TrackerLogin.RedirectAction.SIGN_IN
        TrackerLogin.decide(TrackerLogin.parseRedirect("komikku://bangumi-auth?error=access_denied")!!, "bangumi-auth") shouldBe
            TrackerLogin.RedirectAction.SIGN_OUT
    }

    @Test
    fun `recognizes the app-scheme redirects`() {
        TrackerLogin.isRedirect("komikku://anilist-auth#access_token=abc") shouldBe true
        TrackerLogin.isRedirect("mihon://myanimelist-auth?code=1") shouldBe true
        TrackerLogin.isRedirect("KOMIKKU://Bangumi-Auth?code=1") shouldBe true
        TrackerLogin.isRedirect("tachiyomisy://mangadex-auth?code=1") shouldBe true
        TrackerLogin.isRedirect("https://anilist.co/api/v2/oauth/authorize") shouldBe false
        TrackerLogin.isRedirect("komikku://other-auth?code=1") shouldBe false
    }

    @Test
    fun `parses and decodes parameters`() {
        TrackerLogin.parseParameters("access_token=a%2Bb&token_type=Bearer&flag") shouldBe
            mapOf("access_token" to "a+b", "token_type" to "Bearer", "flag" to null)
        TrackerLogin.parseParameters(null) shouldBe emptyMap()
    }
}
