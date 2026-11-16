package zoukan.desktop.app

import org.junit.jupiter.api.Test
import java.io.File

/**
 * Fails when code that runs coroutines turns a cancellation into an ordinary failure: a plain
 * `runCatching` (use `runCatchingCancellable` around suspend calls) or a `catch (e: Exception|Throwable)`
 * that does not let `CancellationException` through first. A site that wraps no suspension point goes
 * in the allow-list below with its reason; a site is found by its file and a distinctive part of its
 * line, so unrelated edits do not move it.
 */
class CancellationSourceScanTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val scanned = listOf(
        "desktop/src/main/kotlin/zoukan/desktop/ui",
        "desktop/src/main/kotlin/zoukan/desktop/download",
        "desktop/src/main/kotlin/zoukan/desktop/library",
    )

    private val plainRunCatching = Regex("""(?<![A-Za-z])runCatching\s*\{""")
    private val broadCatch = Regex("""catch \((e|_|t|ex|error|it|ignored): (Exception|Throwable)\)""")

    @Test
    fun `no catch or runCatching swallows a coroutine's cancellation`() {
        check(File(root, "desktop/src/main/kotlin").isDirectory) { "Not run from the zoukan checkout: $root" }
        val violations = scanned.map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walk().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                val lines = code(file.readText()).lines()
                lines.withIndex().mapNotNull { (i, line) ->
                    val hit = when {
                        plainRunCatching.containsMatchIn(line) -> true
                        broadCatch.containsMatchIn(line) -> !letsCancellationThrough(lines, i)
                        else -> false
                    }
                    if (hit && !isAllowed(path, line)) "$path:${i + 1}: ${line.trim()}" else null
                }
            }
        check(violations.isEmpty()) {
            "Use runCatchingCancellable around suspend calls, or rethrow CancellationException before this catch " +
                "(or add the site to the allow-list with its reason):\n" + violations.joinToString("\n")
        }
    }

    private fun isAllowed(path: String, line: String): Boolean =
        ALLOWED.any { (file, part, _) -> path.endsWith(file) && part in line }

    private fun letsCancellationThrough(lines: List<String>, i: Int): Boolean {
        val before = lines.subList(maxOf(0, i - 6), i)
        val after = lines.subList(i + 1, minOf(lines.size, i + 4))
        return before.any { "catch (" in it && "CancellationException" in it } ||
            after.any { "CancellationException" in it || "ensureActive()" in it || "isActive" in it }
    }

    /** [text] with comments blanked out, keeping line numbers. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
        .lineSequence()
        .joinToString("\n") { line -> line.replace(Regex("""(^|\s)//.*$"""), "") }

    private data class Site(val file: String, val part: String, val reason: String)

    private companion object {
        const val LINK = "opens a link or folder, no suspension point"
        const val FOCUS = "asks for the keyboard focus, no suspension point"
        const val FILE = "reads a file or a resource, no suspension point"
        const val CALL = "wraps a plain (non-suspend) call"
        const val DECODE = "decodes an image or an animation, no suspension point"
        const val REPORTED = "rethrows CancellationException itself, on the line after"
        const val CLOSE = "closes a file, no suspension point"

        val ALLOWED = listOf(
            Site("ui/browse/BrowseDisplayMode.kt", "runCatching {", "formats a date"),
            Site("ui/browse/ExtensionDetailsScreen.kt", "toHttpUrlOrNull", "removes cookies from the jar, plain call"),
            Site("ui/browse/OpenUrl.kt", "it.getUriType(url)", "the source's plain getUriType"),
            Site("ui/browse/SourceScreenModel.kt", "} catch (e: Throwable) {", "the source's plain getFilterList, called on the UI thread"),
            Site("ui/browse/SourceScreenModel.kt", "filtersJson = runCatching {", "serialises the filters, no suspension point"),
            Site("ui/browse/SourceScreenModel.kt", "mangaDex.fetchRandomMangaUrl()", REPORTED),
            Site("ui/common/AppLanguage.kt", "val english = runCatching", "builds a Locale"),
            Site("ui/common/ConfirmDialog.kt", "cancelFocus.requestFocus()", FOCUS),
            Site("ui/common/LanguageIcons.kt", "fun load(", "parses a bundled icon"),
            Site("ui/common/ReducedMotion.kt", "runCatching {", "reads the system's motion setting"),
            Site("ui/common/SearchField.kt", "focus.requestFocus()", FOCUS),
            Site("ui/common/WindowBounds.kt", "fun screens()", "asks AWT for the screens"),
            Site("ui/common/WindowBounds.kt", "runCatching {", "asks AWT for a screen's insets, no suspension point"),
            Site("ui/library/LibraryScreenModel.kt", "downloadManager.downloadCount", "counts folders, plain call"),
            Site("ui/library/LibrarySearch.kt", "runCatching {", FILE),
            Site("ui/manga/DescriptionImages.kt", "ExternalLinks.browse", LINK),
            Site("ui/manga/DescriptionImages.kt", "DescriptionImageLinks.browse", LINK),
            Site("ui/manga/EntryActions.kt", "http.getMangaUrl", "the source's plain getMangaUrl"),
            Site("ui/manga/MangaScreenModel.kt", "source.getMangaUrl", "the source's plain getMangaUrl"),
            Site("ui/manga/MangaScreenModel.kt", "ExternalLinks.browse", LINK),
            Site("ui/manga/MangaScreenModel.kt", "} catch (e: Exception) {", "opens the entry's folder in the file manager, no suspension point"),
            Site("ui/manga/MetadataDescription.kt", "systemClipboard", "copies to the clipboard"),
            Site("ui/migration/MigrationListScreenModel.kt", "migrateManga(item.manga", REPORTED),
            Site("ui/more/MoreTab.kt", "ExternalLinks.browse", LINK),
            Site("ui/onboarding/OnboardingScreen.kt", "ZoukanStorage", "creates the storage folder"),
            Site("ui/onboarding/OnboardingScreen.kt", "ExternalLinks.browse", LINK),
            Site("ui/onboarding/OnboardingScreen.kt", "fun storageFolderReady(", "checks the folder can be written, no suspension point"),
            Site("ui/reader/PageActions.kt", "} catch (e: Throwable) {", "closes an image and rethrows, no suspension point"),
            Site("ui/reader/PageActions.kt", "} catch (e: Exception) {", DECODE),
            Site("ui/reader/PageAnimation.kt", "runCatching", DECODE),
            Site("ui/reader/PageImage.kt", "} catch (e: Exception) {", DECODE),
            Site("ui/reader/PageImage.kt", "runCatching {", DECODE),
            Site("ui/reader/ReaderEffects.kt", "runCatching", "asks the OS to keep the screen awake"),
            Site("ui/reader/ReaderScreenModel.kt", "orphan.close()", CLOSE),
            Site("ui/reader/ReaderScreenModel.kt", "old.close()", CLOSE),
            Site("ui/reader/ReaderScreenModel.kt", "sourcePreferences.value", "reads a lazy preference"),
            Site("ui/reader/ReaderScreenModel.kt", "downloadManager.isDownloaded", "looks a folder up, plain call"),
            Site("ui/reader/ReaderScreenModel.kt", "source.getChapterUrl", "the source's plain getChapterUrl"),
            Site("ui/reader/ReaderScreenModel.kt", "ExternalLinks.browse", LINK),
            Site("ui/reader/ReaderScreenModel.kt", "} catch (e: Throwable) {", CLOSE),
            Site("ui/recs/RecommendationSearch.kt", "URI(url).path", "parses an address"),
            Site("ui/recs/RecommendsScreen.kt", "ExternalLinks.browse", LINK),
            Site("ui/security/LockOverlay.kt", "focus.requestFocus()", FOCUS),
            Site("ui/settings/AboutSettings.kt", "Updates.install", "starts the installer, no suspension point"),
            Site("ui/settings/AboutSettings.kt", "ExternalLinks.browse", LINK),
            Site("ui/settings/AboutSettings.kt", "disposeAllForQuit", "closes the windows on the way out"),
            Site("ui/settings/AdvancedSettings.kt", "readText()", FILE),
            Site("ui/settings/AdvancedSettings.kt", "cookieJar.removeAll()", CALL),
            Site("ui/settings/DataSettings.kt", "Images.clearDiskCache()", CALL),
            Site("ui/settings/ServerAddress.kt", "URI(address)", "parses an address"),
            Site("ui/settings/ServerAddress.kt", "InetAddress.getByName(literal)", "reads an IP literal, no lookup"),
            Site("ui/settings/SettingsTab.kt", "Desktop.getDesktop().open", LINK),
            Site("ui/settings/SettingsTab.kt", "focus.requestFocus()", FOCUS),
            Site("ui/settings/SourceSettingsScreen.kt", "runCatching", "runs the extension's own plain preference code"),
            Site("ui/settings/SourceSettingsScreen.kt", "} catch (e: Exception) {", "runs the extension's own plain preference code"),
            Site("ui/settings/TrackingSettings.kt", "} catch (e: Throwable) {", "the CancellationException catch above it rethrows"),
            Site("ui/settings/TrackingSettings.kt", "ExternalLinks.browse", LINK),
            Site("ui/track/TrackingDialog.kt", "ExternalLinks.browse", LINK),
            Site("ui/upcoming/UpcomingScreen.kt", "uriHandler.openUri", LINK),
            Site("ui/updates/UpdatesTab.kt", "isDownloaded(downloadManager, item)", "looks a folder up, plain call"),
            Site("ui/updates/UpdatesTab.kt", "downloadManager.downloadedIds", "looks folders up, plain call"),
            Site("ui/whatsnew/ReleaseNotes.kt", "ExternalLinks.browse", LINK),
            Site("ui/whatsnew/WhatsNewScreen.kt", "ExternalLinks.browse", LINK),
            Site("ui/ZoukanApp.kt", "CrashReporter.write", "writes a file"),
            Site("ui/ZoukanApp.kt", "Archives.clearCache()", CALL),
            Site("ui/ZoukanApp.kt", "SystemTray.isSupported()", CALL),
            Site("ui/ZoukanApp.kt", "rootFocus.requestFocus()", FOCUS),
            Site("download/Cbz.kt", "secrets.get(KEY)", "reads the secret store, plain call"),
            Site("download/Cbz.kt", "} catch (e: Exception) {", "reads the secret store, plain call"),
            Site("download/Cbz.kt", "zip.isEncrypted", FILE),
            Site("download/ChapterDownloader.kt", "} catch (e: Throwable) {", "removes the unfinished archive and rethrows"),
            Site("download/DownloadManager.kt", "Injekt.get<SourcePreferences>()", "looks a preference up"),
            Site("download/DownloadManager.kt", "source.getChapterUrl", "the source's plain getChapterUrl"),
            Site("download/DownloadManager.kt", "runCatching {", "saves the queue file: no suspension point inside"),
            Site("download/DownloadManager.kt", "queueFile.readText()", FILE),
            Site("download/TallImageSplitter.kt", "runCatching {", DECODE),
            Site("download/TallImageSplitter.kt", "} catch (e: Exception) {", "writes and moves page files, no suspension point"),
            Site("library/LibraryUpdater.kt", "fun writeErrors(", "writes the error list file"),
            Site("library/PowerStatus.kt", "runCatching", "reads the power state"),
        )
    }
}
