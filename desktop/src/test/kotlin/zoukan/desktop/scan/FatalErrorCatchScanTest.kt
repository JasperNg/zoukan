package zoukan.desktop.scan

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.scan.SourceScan.Allowed

/**
 * A catch-all must let the failures through that the app must not keep running after: an
 * `OutOfMemoryError` that is caught leaves the app running in a broken state, and the crash report
 * never sees it. Every `catch (e: Throwable)` / `catch (e: Error)` (Kotlin) and
 * `catch (Throwable t)` / `catch (Error e)` (Java) in the app's own code must, at the top level of
 * its block (not under an `if`):
 *
 * - call `e.rethrowIfFatal()` (`FatalErrors.rethrowIfFatal(e)` in Java), which rethrows a
 *   `VirtualMachineError` and a `LinkageError`; or
 * - rethrow the caught error itself unconditionally (`throw e`, after cleanup). Wrapping it
 *   (`throw IOException(e)`) does not count: the wrapper is an exception the caller catches.
 *
 * On a path that runs extension code (or loads a native library), a `LinkageError` is an outdated
 * extension (`SourceErrors.kt`) and is shown as an error: there the catch calls
 * `e.rethrowIfVmFailure()` instead, or keeps the older `if (e !is Exception && e !is LinkageError)
 * throw e`. Those are accepted only at the sites [EXTENSION_PATHS] lists, each with what extension
 * code or native library the path reaches.
 *
 * Kotlin's `runCatching` catches every Throwable too. It is left out of this rule: the app's own
 * code has about 300 calls, most around one file or preference operation, and moving them all to a
 * checked helper is a change of its own. The project's `runCatchingCancellable` rethrows the VM's
 * failures (`app/Cancellation.kt`), so code around suspend calls should use it.
 */
class FatalErrorCatchScanTest {
    private val sourceDirs = listOf(
        "desktop/src/main/kotlin",
        "extension-jvm/src/main",
        "android-compat/src/main/java",
    )

    private val sites by lazy {
        SourceScan.sources(sourceDirs).flatMap { source ->
            FatalErrorCatchScanner.sites(source.text).filter { source.isOwn(it.line) }
                .map { it.kind to SourceScan.finding(source, it.line) }
        }
    }

    @Test
    fun `every catch-all rethrows fatal errors`() {
        SourceScan.verify(
            sites.filter { it.first == FatalErrorCatchScanner.Kind.SWALLOWS }.map { it.second },
            ALLOWED,
            "Call e.rethrowIfFatal() first in this catch (xyz.nulldev.androidcompat.util), or rethrow " +
                "the caught error after cleanup (or add the site to the allow-list with its reason):",
        )
    }

    @Test
    fun `a catch-all that keeps a LinkageError is on a path that runs extension code`() {
        SourceScan.verify(
            sites.filter { it.first == FatalErrorCatchScanner.Kind.KEEPS_LINKAGE }.map { it.second },
            EXTENSION_PATHS,
            "rethrowIfVmFailure() is only for a path that runs extension code or loads a native library; " +
                "use rethrowIfFatal() here (or list the site with what extension code it runs):",
        )
    }

    @Test
    fun `flags every catch-all without a rethrow, a conditional rethrow and a wrapper`() {
        FatalErrorCatchScanner.lines(
            """
            fun once() {
                try { step() } catch (e: Throwable) { log(e) }
                try { step() } catch (e: Error) { log(e) }
                try { a() } catch (e: Throwable) {
                    if (e is VirtualMachineError) throw e
                }
                try { a() } catch (e: Throwable) { throw IOException("wrapped", e) }
                try { a() } catch (e: Throwable) {
                    if (bad) e.rethrowIfFatal()
                }
                try { a() } catch (e: Throwable) { other.rethrowIfFatal() }
                try { a() } catch (e: Throwable) { CrashReporter.fatal(e) }
                try { a() } catch (e: Throwable) { if (x) throw e }
            }
            class Java {
                void run() {
                    try { step(); } catch (final Throwable t) { Log.e(TAG, "threw", t); }
                    try { a(); } catch (Error error) {
                        if (error instanceof VirtualMachineError) { throw error; }
                    }
                }
            }
            """.trimIndent(),
        ) shouldBe listOf(2, 3, 4, 7, 8, 11, 12, 13, 17, 18)
    }

    @Test
    fun `accepts the helper, an unconditional rethrow and narrower catches`() {
        FatalErrorCatchScanner.lines(
            """
            fun accepted() {
                try { a() } catch (e: Throwable) { e.rethrowIfFatal(); log(e) }
                try { a() } catch (e: Throwable) { cleanUp(); throw e }
                try { a() } catch (e: Throwable) {
                    toast(e)
                    e.rethrowIfFatal()
                }
                try { a() } catch (e: Exception) { log(e) }
                try { a() } catch (e: CancellationException) { log(e) }
                val x = try { a() } catch (t: Throwable) {
                    t.rethrowIfFatal()
                    null
                }
            }
            class Java {
                void run() {
                    try { a(); } catch (Throwable t) { FatalErrors.rethrowIfFatal(t); log(t); }
                    try { a(); } catch (Error e) { cleanUp(); throw e; }
                }
            }
            """.trimIndent(),
        ) shouldBe emptyList()
    }

    @Test
    fun `tells the extension form apart`() {
        FatalErrorCatchScanner.sites(
            """
            fun extension() {
                try { a() } catch (e: Throwable) { e.rethrowIfVmFailure(); log(e) }
                try { a() } catch (e: Throwable) {
                    // A refusal, or the native store failing to load
                    if (e !is Exception && e !is LinkageError) throw e
                    log(e)
                }
                try { a() } catch (e: Throwable) { e.rethrowIfFatal() }
            }
            class Java {
                void run() {
                    try { a(); } catch (Error error) { xyz.nulldev.androidcompat.util.FatalErrors.rethrowIfVmFailure(error); }
                }
            }
            """.trimIndent(),
        ).map { it.line to it.kind } shouldBe listOf(
            2 to FatalErrorCatchScanner.Kind.KEEPS_LINKAGE,
            3 to FatalErrorCatchScanner.Kind.KEEPS_LINKAGE,
            12 to FatalErrorCatchScanner.Kind.KEEPS_LINKAGE,
        )
    }

    private companion object {
        val ALLOWED = listOf(
            Allowed("zoukan/desktop/Main.kt", "} catch (e: Throwable) {", "the start-up's last handler: CrashReporter.fatal reports every failure, fatal ones included, and ends the app", function = "dispatch"),
            Allowed("app/AppShutdown.kt", "} catch (e: Throwable) {", "the last step before the process exits: the failure is logged and the exit goes on; a rethrow could keep the process from exiting", function = "runForApp"),
            Allowed("network/AppDnsResolver.kt", "} catch (_: Throwable) {", "loading the JDK's resolver provider: an error escaping it leaves InetAddress unusable for the whole run, so the JDK's own resolver is used instead", function = "get"),
            Allowed("image/FfmpegImageDecoder.kt", "} catch (_: Throwable) {", "a read or seek callback from FFmpeg's native code: nothing may be thrown back into native code; the error code ends the decode, which then fails", function = "call", count = 2),
            Allowed("security/SecureScreen.kt", "} catch (e: Throwable) {", "a callback from macOS's main dispatch queue through JNA: nothing may be thrown back into native code", function = "invoke"),
        )

        const val SOURCE = "runs extension code: a source's"
        val EXTENSION_PATHS = listOf(
            // The helpers themselves, for their callers
            Allowed("app/Cancellation.kt", "} catch (e: Throwable) {", "runs extension code: runCatchingCancellable wraps calls into sources, trackers and the extension manager"),
            Allowed("ui/common/ReportingLaunch.kt", "} catch (e: Throwable) {", "runs extension code: launchReporting runs any user action, refreshing an entry from its source included", function = "launchReporting"),
            Allowed("android/os/Looper.java", "} catch (Error error) {", "runs extension code: the main looper runs every extension's handler and WebView callbacks", function = "loopOnce"),
            // Sources
            Allowed("ui/reader/ReaderScreenModel.kt", "} catch (e: Throwable) {", "$SOURCE page list, page images and page actions", count = 4),
            Allowed("ui/browse/SourceScreenModel.kt", "} catch (e: Throwable) {", "$SOURCE filters and browse pages", count = 3),
            Allowed("ui/browse/GlobalSearchScreen.kt", "} catch (e: Throwable) {", "$SOURCE search"),
            Allowed("ui/browse/FeedTab.kt", "} catch (e: Throwable) {", "$SOURCE feed"),
            Allowed("ui/manga/MangaScreenModel.kt", "} catch (e: Throwable) {", "$SOURCE details and chapters, and enhanced trackers that ask the source", count = 3),
            Allowed("ui/manga/MergeAndMigrate.kt", "} catch (e: Throwable) {", "$SOURCE search for a merge or a migration", count = 2),
            Allowed("ui/manga/PagePreviews.kt", "} catch (e: Throwable) {", "$SOURCE page previews", count = 2),
            Allowed("ui/manga/RelatedTitles.kt", "} catch (e: Throwable) {", "$SOURCE related titles", function = "RelatedTitlesSection"),
            Allowed("ui/recs/RecommendationSearch.kt", "} catch (e: Throwable) {", "$SOURCE search for recommendations"),
            Allowed("download/DownloadManager.kt", "} catch (e: Throwable) {", "$SOURCE page list and page images for a download", function = "download"),
            Allowed("download/ChapterDownloader.kt", "} catch (e: Throwable) {", "$SOURCE page list and page images, retried", function = "retry"),
            Allowed("ui/settings/EhSettings.kt", "} catch (e: Throwable) {", "$SOURCE gallery update (the E-Hentai source)"),
            Allowed("track/TrackingModel.kt", "} catch (e: Throwable) {", "$SOURCE metadata, and enhanced trackers that ask the source", count = 3),
            Allowed("exh/EhGalleryUpdater.kt", "} catch (t: Throwable) {", "$SOURCE gallery update (the E-Hentai source)", function = "updateEntryAndGetChapters"),
            Allowed("library/AddToLibrary.kt", "} catch (e: Throwable) {", "$SOURCE details and chapters after adding an entry"),
            Allowed("library/BackgroundJobs.kt", "} catch (e: Throwable) {", "runs extension code: the scheduled library update, gallery update and tracker retries"),
            Allowed("library/LibraryUpdater.kt", "} catch (e: Throwable) {", "$SOURCE details and chapters in a library update", count = 2),
            // Loading and managing extensions
            Allowed("ui/settings/MissingExtensions.kt", "} catch (e: Throwable) {", "runs extension code: installing an extension loads it", function = "installMissingExtensions"),
            Allowed("ui/settings/MissingExtensionsInstall.kt", "} catch (e: Throwable) {", "runs extension code: installing extensions loads them", count = 2),
            Allowed("ui/browse/BrowseScreenModel.kt", "} catch (e: Throwable) {", "runs extension code: installing or updating an extension loads it"),
            Allowed("ui/browse/ExtensionDetailsScreen.kt", "} catch (e: Throwable) {", "runs extension code: uninstalling an extension unloads its sources", function = "uninstall"),
            Allowed("zoukan/extension/DesktopExtensionLoader.kt", "} catch (e: Throwable) {", "runs extension code: loading an extension's classes and creating its sources, which fail with a LinkageError when it is outdated", count = 2),
            // The extension test harnesses (run on demand, not by the app)
            Allowed("harness/ExtensionWiringCheck.kt", "} catch (e: Throwable) {", "runs extension code: the extension-wiring check installs extensions and calls their sources and settings, recording each failure", count = 4),
            Allowed("harness/CompatibilityHarness.kt", "} catch (e: Throwable) {", "runs extension code: the compatibility harness calls each source's optional steps", function = "optional"),
            // Native libraries through JNA
            Allowed("ui/settings/SecuritySettings.kt", "} catch (e: Throwable) {", "loads a native library: the OS secret store through JNA, which fails with a LinkageError when it cannot load"),
            Allowed("ui/security/LockOverlay.kt", "} catch (e: Throwable) {", "loads a native library: the OS secret store through JNA"),
            Allowed("ui/settings/DownloadPasswordDialog.kt", "} catch (e: Throwable) {", "loads a native library: the OS secret store through JNA (the older idiom, which also rethrows every other Error)"),
            Allowed("ui/settings/AboutSettings.kt", "} catch (e: Throwable) {", "loads a native library: the OS secret store through JNA (the older idiom, which also rethrows every other Error)"),
            Allowed("update/UpdateStates.kt", "} catch (e: Throwable) {", "loads a native library: the update check reads its token from the OS secret store through JNA", function = "check"),
        )
    }
}

/** Finds the catch-alls [FatalErrorCatchScanTest] forbids or restricts. */
internal object FatalErrorCatchScanner {
    enum class Kind {
        /** Lets no fatal error through. */
        SWALLOWS,

        /** Rethrows the VM's failures but keeps a LinkageError: only on a path that runs extension code. */
        KEEPS_LINKAGE,
    }

    data class Site(val line: Int, val kind: Kind)

    private val KOTLIN_CATCH = Regex("""\bcatch\s*\(\s*(\w+)\s*:\s*(?:kotlin\.|java\.lang\.)?(?:Throwable|Error)\s*\)""")
    private val JAVA_CATCH = Regex("""\bcatch\s*\(\s*(?:final\s+)?(?:[\w.]+\s*\|\s*)*(?:java\.lang\.)?(?:Throwable|Error)\s+(\w+)\s*\)""")

    /** The 1-based lines of the catch-alls that swallow fatal errors. */
    fun lines(text: String): List<Int> = sites(text).filter { it.kind == Kind.SWALLOWS }.map { it.line }

    fun sites(text: String): List<Site> {
        val source = SourceScan.Source("", text)
        val m = source.masked
        val catches = (KOTLIN_CATCH.findAll(m) + JAVA_CATCH.findAll(m)).map { it to it.groupValues[1] }
        return catches.mapNotNull { (match, name) ->
            val open = m.indexOf('{', match.range.last)
            if (open < 0) return@mapNotNull null
            val block = m.substring(open + 1, SourceScan.closing(m, open))
            val kind = classify(block, name) ?: return@mapNotNull null
            Site(source.lineOf(match.range.first), kind)
        }.sortedBy { it.line }.toList()
    }

    /** Null when [block] lets every fatal error through. */
    private fun classify(block: String, name: String): Kind? {
        val n = Regex.escape(name)
        val topLevel = topLevelText(block)
        // An unconditional rethrow of the caught error itself
        if (Regex("""(?:^|[;\n{])\s*throw\s+$n\s*(?:;|$)""", RegexOption.MULTILINE).containsMatchIn(topLevel)) return null
        if (Regex("""(?:\b$n\.rethrowIfFatal\s*\(\s*\)|\bFatalErrors\.rethrowIfFatal\s*\(\s*$n\s*\))""").containsMatchIn(topLevel)) return null
        val keepsLinkage = Regex("""(?:\b$n\.rethrowIfVmFailure\s*\(\s*\)|\bFatalErrors\.rethrowIfVmFailure\s*\(\s*$n\s*\))""").containsMatchIn(topLevel) ||
            Regex("""(?m)^\s*if\s*\(\s*$n\s*!is\s+Exception\s*&&\s*$n\s*!is\s+LinkageError\s*\)\s*throw\s+$n\s*$""").containsMatchIn(block)
        return if (keepsLinkage) Kind.KEEPS_LINKAGE else Kind.SWALLOWS
    }

    /** [block] without its nested blocks, and without lines that start with `if`/`when`/`else`. */
    private fun topLevelText(block: String): String {
        val out = StringBuilder()
        var depth = 0
        block.forEach { c ->
            when (c) {
                '{' -> depth++
                '}' -> depth--
                else -> if (depth == 0) out.append(c)
            }
        }
        return out.lines().filterNot { Regex("""^\s*(?:if|when|else|\?:|\|\||&&)\b""").containsMatchIn(it) || "?:" in it }
            .joinToString("\n")
    }
}
