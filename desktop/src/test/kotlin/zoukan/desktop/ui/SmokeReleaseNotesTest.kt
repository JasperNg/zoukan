package zoukan.desktop.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.app.AppVersion
import zoukan.desktop.app.Version
import zoukan.desktop.secrets.FileSecretStore
import zoukan.desktop.update.UpdateChecker
import java.io.File
import java.io.IOException

/** The smoke checks' What's new release: the same notes for every version, with no network. */
class SmokeReleaseNotesTest {

    @TempDir
    lateinit var temp: File

    private fun checker() = SmokeReleaseNotes.checker(FileSecretStore(File(temp, "secrets")))

    @Test
    fun `the running version's notes are the fixed release, whatever GitHub holds for it`() = runBlocking<Unit> {
        val notes = checker().releaseNotes().getOrThrow()

        notes.name shouldBe SmokeReleaseNotes.NAME
        notes.body shouldBe SmokeReleaseNotes.BODY
        // The link sits in a paragraph that starts as usability-allowlist.txt's entry for it
        notes.body shouldContain "\nFull changes: [the compare page](https://"
    }

    @Test
    fun `any other release and the update check fail as offline`() = runBlocking<Unit> {
        checker().releaseNotes(Version(AppVersion.version.parts + 1))
            .exceptionOrNull().shouldBeInstanceOf<IOException>()
        checker().check(includePrereleases = false).shouldBeInstanceOf<UpdateChecker.Result.Failed>()
    }
}
