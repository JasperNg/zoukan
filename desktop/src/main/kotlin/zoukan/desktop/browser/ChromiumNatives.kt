package zoukan.desktop.browser

import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import logcat.LogPriority
import me.friwi.jcefmaven.CefBuildInfo
import me.friwi.jcefmaven.EnumPlatform
import me.friwi.jcefmaven.impl.step.check.CefInstallationChecker
import me.friwi.jcefmaven.impl.step.extract.TarGzExtractor
import me.friwi.jcefmaven.impl.step.fetch.PackageClasspathStreamer
import me.friwi.jcefmaven.impl.util.macos.UnquarantineUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Installs Chromium's native code (about 150 MB, downloaded the first time the browser is needed)
 * and checks it first.
 *
 * jcefmaven's own download takes whatever the server sends and runs it. Here the package is
 * downloaded by the app, its SHA-256 is compared with the one pinned in `jcef-natives.sha256` for
 * this exact JCEF build and platform, and only then is it unpacked the way jcefmaven does. Without
 * a pinned checksum, or with another one, nothing is installed.
 *
 * The download uses the app's HTTP client, so DNS over HTTPS applies to it.
 */
object ChromiumNatives {
    private val MIRRORS = listOf(
        "https://repo.maven.apache.org/maven2/me/friwi/jcef-natives-{platform}/{tag}/jcef-natives-{platform}-{tag}.jar",
        "https://github.com/jcefmaven/jcefmaven/releases/download/{mvn_version}/jcef-natives-{platform}-{tag}.jar",
    )
    private const val PINS = "/jcef-natives.sha256"
    private const val DOWNLOAD = "download.zip.temp"

    /** Does nothing when this JCEF build is installed in [installDir], or its natives are bundled. */
    fun ensureInstalled(installDir: File, client: () -> OkHttpClient) {
        if (CefInstallationChecker.checkInstallation(installDir)) return
        val info = CefBuildInfo.fromClasspath()
        val platform = EnumPlatform.getCurrentPlatform()
        // Natives on the class path came with the app; jcefmaven unpacks those itself
        PackageClasspathStreamer.streamNatives(info, platform)?.use { return }

        val expected = pinnedSha256(platform.identifier, info.releaseTag)
            ?: throw IOException(
                "No pinned checksum for Chromium ${info.releaseTag} on ${platform.identifier}; it is not downloaded",
            )
        installDir.deleteRecursively()
        if (!installDir.mkdirs()) throw IOException("Could not create $installDir")
        val download = File(installDir, DOWNLOAD)
        try {
            fetch(download, expected, platform.identifier, info.releaseTag, client().forDownload())
            logcat { "Chromium: unpacking" }
            ZipFile(download).use { zip ->
                val archive = zip.entries().asSequence().firstOrNull { it.name.endsWith(".tar.gz") }
                    ?: throw IOException("The Chromium package has no .tar.gz archive")
                zip.getInputStream(archive).use { TarGzExtractor.extractTarGZ(installDir, it) }
            }
        } catch (e: Throwable) {
            // Nothing half installed stays behind
            installDir.deleteRecursively()
            throw e
        }
        download.delete()
        if (platform.os.isMacOSX) UnquarantineUtil.unquarantine(installDir)
        if (!File(installDir, "install.lock").createNewFile()) throw IOException("Could not complete the Chromium installation")
    }

    private fun fetch(target: File, expected: String, platform: String, tag: String, client: OkHttpClient) {
        var last: Exception? = null
        for (mirror in MIRRORS) {
            val url = mirror.replace("{platform}", platform).replace("{tag}", tag).replace("{mvn_version}", mavenVersion())
            try {
                val actual = download(url, target, client)
                if (actual.equals(expected, ignoreCase = true)) return
                target.delete()
                throw IOException("The Chromium package from ${Request.Builder().url(url).build().url.host} has the wrong checksum ($actual)")
            } catch (e: IOException) {
                logcat(LogPriority.WARN) { "Chromium: ${e.message}" }
                last = e
            }
        }
        throw IOException("Chromium could not be downloaded", last)
    }

    /** Downloads [url] to [target] and returns the SHA-256 of what was written. */
    private fun download(url: String, target: File, client: OkHttpClient): String {
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${response.request.url.host}")
            val total = response.body.contentLength()
            var read = 0L
            var shown = -1L
            response.body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        val percent = if (total > 0) read * 100 / total else -1
                        if (percent >= shown + 10) {
                            shown = percent
                            logcat { "Downloading Chromium: $percent%" }
                        }
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The app's client without the Cloudflare solver (which needs Chromium) and its two-minute limit. */
    private fun OkHttpClient.forDownload(): OkHttpClient = newBuilder()
        .apply { interceptors().removeAll { it is CloudflareInterceptor } }
        .callTimeout(1, TimeUnit.HOURS)
        .build()

    private fun mavenVersion(): String =
        ChromiumNatives::class.java.getResourceAsStream("/jcefmaven_build_meta.json")
            ?.use { Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(it.readBytes().decodeToString())?.groupValues?.get(1) }
            .orEmpty()

    /** The pinned SHA-256 of the natives package for [platform] and JCEF build [tag], if any. */
    internal fun pinnedSha256(platform: String, tag: String): String? = pins()[platform to tag]

    /** Lines of `<sha256> <platform> <tag>`; # starts a comment. */
    internal fun pins(): Map<Pair<String, String>, String> =
        ChromiumNatives::class.java.getResourceAsStream(PINS)
            ?.use { it.readBytes().decodeToString() }
            .orEmpty()
            .lineSequence()
            .map { it.substringBefore('#').trim().split(Regex("\\s+")) }
            .filter { it.size == 3 }
            .associate { (sha, platform, tag) -> (platform to tag) to sha }
}
