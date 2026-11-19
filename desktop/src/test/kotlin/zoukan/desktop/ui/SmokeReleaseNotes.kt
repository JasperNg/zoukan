package zoukan.desktop.ui

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import zoukan.desktop.app.AppVersion
import zoukan.desktop.secrets.SecretStore
import zoukan.desktop.update.UpdateChecker
import java.io.IOException

/**
 * The release notes the smoke checks show on What's new, in place of GitHub's. The screen reads
 * the running version's release from GitHub, so the checks went online and their usability
 * findings changed with what a published release said: notes that GitHub generated (a line per
 * pull request with its link) failed the run, notes without links passed, and a version with no
 * release showed an error instead; a slow answer left the screen loading when it was checked. A
 * fixed release, answered at once, keeps the checks offline and the same on every machine and
 * every day. Its body has a link in a paragraph, as Zoukan's notes do, so the link's
 * rendering stays covered.
 */
internal object SmokeReleaseNotes {
    const val NAME = "Zoukan smoke release"

    const val BODY = "### Fixes\n\n- A fix.\n\nFull changes: [the compare page](https://example.invalid/compare)\n"

    /** A checker whose client answers the release of [AppVersion.version] and nothing else, without the network. */
    fun checker(secrets: SecretStore): UpdateChecker {
        val tagPath = "/repos/${AppVersion.releaseRepo}/releases/tags/${AppVersion.TAG_PREFIX}${AppVersion.version}"
        val release = """{"tag_name": "${AppVersion.TAG_PREFIX}${AppVersion.version}", "name": "$NAME", "body": ${quote(BODY)}, "html_url": ""}"""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                // Any other request (the update check) fails as it would offline
                if (request.url.encodedPath != tagPath) throw IOException("The smoke checks are offline")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(release.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return UpdateChecker(client, secrets)
    }

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
