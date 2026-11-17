package zoukan.desktop.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import zoukan.desktop.app.SelfTest.Status
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.update.UpdateChecker

/** The install checks' update check: a GitHub rate limit is "skipped", never a pass. */
class SelfTestUpdateCheckTest {
    private object NoSecrets : SecretStore {
        override val name = "none"
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) = Unit
        override fun delete(key: String) = Unit
    }

    private fun answering(code: Int, vararg headers: Pair<String, String>) = UpdateChecker(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .body("{}".toResponseBody()).build()
        }.build(),
        NoSecrets,
        repo = "o/r",
    )

    private fun check(code: Int, vararg headers: Pair<String, String>) = runBlocking { answering(code, *headers).check() }

    @Test
    fun `GitHub's rate limit answers are marked`() {
        (check(403, "x-ratelimit-remaining" to "0") as UpdateChecker.Result.Failed).rateLimited shouldBe true
        (check(403, "retry-after" to "60") as UpdateChecker.Result.Failed).rateLimited shouldBe true
        (check(429) as UpdateChecker.Result.Failed).rateLimited shouldBe true
        (check(403, "x-ratelimit-remaining" to "12") as UpdateChecker.Result.Failed).rateLimited shouldBe false
        (check(401) as UpdateChecker.Result.Failed).rateLimited shouldBe false
    }

    @Test
    fun `a rate limit is skipped, with or without a token`() {
        val limited = UpdateChecker.Result.Failed("GitHub rate limit or no access (403); try again later", rateLimited = true)
        SelfTest.updateCheckOutcome(limited, withToken = false).status shouldBe Status.SKIPPED
        SelfTest.updateCheckOutcome(limited, withToken = true).status shouldBe Status.SKIPPED
        SelfTest.updateCheckOutcome(limited, withToken = false).detail.startsWith("skipped (rate limited)") shouldBe true
    }

    @Test
    fun `no connection fails`() {
        SelfTest.updateCheckOutcome(UpdateChecker.Result.Failed("Could not reach GitHub: timeout"), withToken = false).status shouldBe Status.FAIL
    }

    @Test
    fun `without a token a private repository's not found still shows GitHub was reached`() {
        val notFound = UpdateChecker.Result.Failed("No releases found. If the repository is private, add a GitHub token.")
        SelfTest.updateCheckOutcome(notFound, withToken = false).status shouldBe Status.PASS
    }

    @Test
    fun `with a token any other failure fails`() {
        SelfTest.updateCheckOutcome(UpdateChecker.Result.Failed("GitHub refused the token (401)"), withToken = true).status shouldBe Status.FAIL
        SelfTest.updateCheckOutcome(UpdateChecker.Result.Failed("GitHub answered 500"), withToken = true).status shouldBe Status.FAIL
    }

    @Test
    fun `up to date passes and never names the token`() {
        val outcome = SelfTest.updateCheckOutcome(UpdateChecker.Result.UpToDate, withToken = true)
        outcome.status shouldBe Status.PASS
        outcome.detail shouldNotContain "Bearer"
    }
}
