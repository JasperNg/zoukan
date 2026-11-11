package zoukan.desktop.track

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackerLoginTest {

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
