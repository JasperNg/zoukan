package zoukan.desktop.update

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import zoukan.desktop.app.Version
import zoukan.desktop.secrets.SecretStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The update check against the real GitHub releases: an app at [FROM] finds a newer release,
 * downloads this platform's installer and its `.sig`, and the release signature verifies under
 * the public key committed in `zoukan-release-key.pem`; a copy with one byte flipped is refused.
 *
 * Needs the internet and downloads one installer (about 300 MB), so it runs only when asked:
 *   ZOUKAN_LIVE=true ./gradlew :desktop:test --tests "*UpdateLiveTest" --rerun
 * (or -Dzoukan.live=true on the test JVM). `ZOUKAN_LIVE_UPDATE_FROM=1.0.6` sets the version the
 * check runs as. It uses an in-memory secret store (no GitHub token, nothing read from the OS
 * store) and a temporary folder: no app data is touched.
 *
 * Releases published before the release signature have no `.sig`. For those the test checks
 * that the app refuses the installer (fails closed) and then skips the signature checks.
 */
class UpdateLiveTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the newest release is found, downloaded and its release signature verifies`(): Unit = runBlocking {
        assumeTrue(System.getProperty("zoukan.live") == "true" || System.getenv("ZOUKAN_LIVE") == "true")
        val from = Version.parse(System.getenv("ZOUKAN_LIVE_UPDATE_FROM") ?: FROM).shouldNotBeNull()
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.MINUTES).build()
        val checker = UpdateChecker(client, NoSecrets, current = from)

        val result = checker.check()
        println("Update check from $from: $result")
        val release = result.shouldBeInstanceOf<UpdateChecker.Result.Available>().release
        (release.version > from) shouldBe true
        val asset = release.asset.shouldNotBeNull()
        println("Installer ${asset.name}, ${asset.size} bytes, sha256 ${asset.sha256}, signature ${asset.signatureApiUrl != null}")
        asset.sha256.shouldNotBeNull()

        val installer = checker.download(asset, dir)
        installer.length() shouldBe asset.size
        val signature = checker.downloadSignature(asset, dir)
        val key = ReleaseSignature.builtIn()

        if (signature == null) {
            // A release from before the release signature: the app must refuse it
            key.verify(installer, null).shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()
            ReleaseSignature(publicKeyPem = null, packaged = true).verify(installer, null)
                .shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()
            println("${release.tag} has no release signature: refused, as an unsigned installer must be")
            assumeTrue(false, "${release.tag} carries no .sig; the signature checks need a signed release")
        }
        signature!!.length() shouldBe ReleaseSignature.SIGNATURE_BYTES

        val verdict = key.verify(installer, signature)
        println("Release signature of ${installer.name}: $verdict")
        verdict.shouldBeInstanceOf<InstallerSignature.Verdict.Trusted>()

        // The same file with one byte flipped, under the same name: the SHA-256 in the statement changes
        val tampered = File(dir, "tampered").apply { mkdirs() }.resolve(installer.name)
        installer.copyTo(tampered)
        java.io.RandomAccessFile(tampered, "rw").use { file ->
            val at = file.length() / 2
            file.seek(at)
            val b = file.read()
            file.seek(at)
            file.write(b xor 0x01)
        }
        val tamperedVerdict = key.verify(tampered, signature)
        println("Release signature of the tampered copy: $tamperedVerdict")
        tamperedVerdict.shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()

        // A signature that is not the release's (one byte flipped) is refused as well
        val badSignature = File(dir, "bad.sig").apply {
            writeBytes(signature.readBytes().also { it[0] = (it[0].toInt() xor 0x01).toByte() })
        }
        key.verify(installer, badSignature).shouldBeInstanceOf<InstallerSignature.Verdict.Rejected>()
    }

    private object NoSecrets : SecretStore {
        override val name = "none"
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) = Unit
        override fun delete(key: String) = Unit
    }

    private companion object {
        const val FROM = "1.0.6"
    }
}
