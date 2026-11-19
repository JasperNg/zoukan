package zoukan.desktop.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import zoukan.desktop.app.SelfTest.Status
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.update.UpdateChecker
import java.io.File
import java.io.IOException

class SelfTestModulesTest {
    @Test
    fun `the modules the self-test checks are in the runtime and not marked for removal`() {
        val problems = SelfTest.checkedModules.mapNotNull { name ->
            val module = ModuleLayer.boot().findModule(name).orElse(null)
                ?: return@mapNotNull "$name is not in this runtime"
            val deprecated = module.getAnnotation(java.lang.Deprecated::class.java)
            if (deprecated?.forRemoval == true) "$name is deprecated for removal" else null
        }
        problems shouldBe emptyList()
    }
}

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

class VersionTest {
    @Test
    fun `parses and compares dotted versions`() {
        Version.parse("1.2.3") shouldBe Version(listOf(1, 2, 3))
        Version.parse("v1.10") shouldBe Version(listOf(1, 10))
        Version.parse("1.2.3-beta") shouldBe null
        Version.parse("desktop-v1.0.0") shouldBe null
        Version.parse("1.10.0")!! shouldBeGreaterThan Version.parse("1.9.9")!!
        Version.parse("2.0")!! shouldBeGreaterThan Version.parse("1.99.99")!!
        Version.parse("1.0")!!.compareTo(Version.parse("1.0.0")!!) shouldBe 0
    }

    @Test
    fun `a part too long for an Int is not a version instead of a crash`() {
        Version.parse("1.99999999999999999999") shouldBe null
        Version.parse("2147483648") shouldBe null
        Version.parse("2147483647") shouldBe Version(listOf(Int.MAX_VALUE))
    }

    @Test
    fun `the build writes the version`() {
        AppVersion.name shouldBe AppVersion.version.toString()
        AppVersion.version shouldBeGreaterThan Version(listOf(0, 9))
    }
}

/** What runCatchingCancellable turns into a failure, and what it lets through (Cancellation.kt). */
class RunCatchingCancellableTest {
    @Test
    fun `an exception and an outdated extension's LinkageError become a failure`() {
        runCatchingCancellable { throw IOException("offline") }.exceptionOrNull()?.message shouldBe "offline"
        runCatchingCancellable { throw NoSuchMethodError("removed") }.isFailure shouldBe true
        runCatchingCancellable { 1 }.getOrNull() shouldBe 1
    }

    @Test
    fun `a cancel and running out of memory go on up`() {
        shouldThrow<CancellationException> { runCatchingCancellable { throw CancellationException("closed") } }
        shouldThrow<OutOfMemoryError> { runCatchingCancellable { throw OutOfMemoryError() } }
    }
}

/**
 * Deleting downloads throws when the storage drive is unplugged (StorageUnavailableException).
 * Fails when a screen deletes downloads in a function that neither launches it with
 * `launchReporting` nor catches the failure, so the user would see nothing and the error would go
 * to the crash report.
 */
class DeleteFailureReportedScanTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val deleteCall = Regex("""downloadManager\.(delete|deleteManga)\(""")
    private val function = Regex("""^\s*(?:(?:private|internal|override|public)\s+)*(?:suspend\s+)?fun\s""")

    @Test
    fun `every screen that deletes downloads reports a failure`() {
        val ui = File(root, "desktop/src/main/kotlin/zoukan/desktop/ui")
        check(ui.isDirectory) { "Not run from the zoukan checkout: $root" }
        val violations = ui.walk().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val lines = file.readLines()
            lines.withIndex().filter { (_, line) -> deleteCall.containsMatchIn(line) }.mapNotNull { (i, line) ->
                // The function the call is in, from its declaration down to the call
                val start = (i downTo 0).firstOrNull { function.containsMatchIn(lines[it]) } ?: 0
                val body = lines.subList(start, i + 1)
                // A suspend function hands the failure to its caller, which the screen tests check
                if (Regex("""\bsuspend\s+fun\s""").containsMatchIn(lines[start])) return@mapNotNull null
                val reported = body.any { "launchReporting" in it || "catch (" in it || "try {" in it.trim() }
                if (reported) null else "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1}: ${line.trim()}"
            }
        }.toList()
        check(violations.isEmpty()) {
            "Launch these with launchReporting or catch the failure and show it:\n" + violations.joinToString("\n")
        }
    }
}
