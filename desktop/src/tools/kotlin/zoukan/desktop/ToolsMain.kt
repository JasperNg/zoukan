package zoukan.desktop

import eu.kanade.tachiyomi.data.backup.BackupCodec
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.PREF_DOH_360
import eu.kanade.tachiyomi.network.PREF_DOH_ADGUARD
import eu.kanade.tachiyomi.network.PREF_DOH_ALIDNS
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_CONTROLD
import eu.kanade.tachiyomi.network.PREF_DOH_DNSPOD
import eu.kanade.tachiyomi.network.PREF_DOH_GOOGLE
import eu.kanade.tachiyomi.network.PREF_DOH_MULLVAD
import eu.kanade.tachiyomi.network.PREF_DOH_NJALLA
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import eu.kanade.tachiyomi.network.dohProviderUrl
import mihon.data.extension.service.ExtensionStoreService
import okio.sink
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.SingleInstance
import zoukan.desktop.backup.BackupCheck
import zoukan.desktop.backup.BackupComparison
import zoukan.desktop.backup.DesktopBackups
import zoukan.desktop.backup.NoStubSources
import zoukan.desktop.backup.SyntheticBackup
import zoukan.desktop.harness.CompatibilityHarness
import zoukan.desktop.harness.ExtensionWiringCheck
import zoukan.extension.DesktopExtensionInstaller
import zoukan.extension.DesktopExtensionLoader
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val TOOLS_USAGE = """Zoukan desktop tools

Usage: zoukan-tools <command> [options]

Commands:
  harness [options]       Run the extension compatibility harness and write a report
      --store <url>         Extension store index URL (required), repeatable: each store gets its
                            own report
      --packages <a,b,...>  Extensions to test, for example en.mangapill,all.mangadex; over several
                            stores, each store tests those it lists
      --all                 Test every extension in the store
      --lang <code>         Source language to test in multi-language extensions (default: en)
      --offline             Install and load only, no calls to manga sites
      --apk                 Install from APKs with dex2jar even when the store has jars
      --skip-nsfw           With --all, skip NSFW extensions
      --interactive         Open a browser window for Cloudflare challenges the hidden browser cannot solve
      --doh <name|off>      DNS over HTTPS for the run: cloudflare, google, adguard, quad9, alidns, dnspod,
                            360, mullvad, controld, njalla, or off (default: the harness profile's setting)
      --wiring            Check every extension (or --packages) is wired up, without calling manga sites:
                            missing Android or app APIs, Injekt lookups, sources, client and headers, settings
  backup check <file>     Restore a backup into an empty profile, back it up again, and compare the two
      --report <file.md>    Also write the result as a Markdown report
      --keep                Keep the throwaway profile
  backup compare <a> <b>  Compare two backups field by field (what b lost or changed from a)
  backup synthetic <file> Write a backup with every field set, for testing
      --legacy              Use older forms of some fields
      --force               Replace <file> if it exists

Data folder: -Dzoukan.dataDir=<path> or ZOUKAN_DATA_DIR overrides the default.

Exit codes: 0 success, 1 the command failed, 2 bad usage. harness exits 1 when an extension fails to
install or load or a store cannot be read, and with --wiring also when an app API is missing.
"""

fun main(args: Array<String>) = runCliMain(args, ::runToolsCommand)

/**
 * Runs one developer-tool command line and returns its exit code: 0 success, 1 the command failed,
 * 2 bad usage. It does not throw for a failing command, so [main] always reaches its shutdown path.
 */
internal fun runToolsCommand(args: Array<String>): Int =
    runCliCommand(args, TOOLS_USAGE, loggerName = "zoukan.tools", dispatch = ::dispatchTools)

private fun dispatchTools(command: String, rest: List<String>): Int = when (command) {
    "harness" -> harness(rest)
    "backup" -> toolsBackup(rest)
    "help", "--help", "-h" -> {
        println(TOOLS_USAGE)
        0
    }
    else -> {
        System.err.println("Unknown command $command\n\n$TOOLS_USAGE")
        2
    }
}

private fun harness(args: List<String>): Int {
    var options = CompatibilityHarness.Options()
    val stores = mutableListOf<String>()
    var wiring = false
    var doh: Int? = null
    val it = args.iterator()
    while (it.hasNext()) {
        when (val arg = it.next()) {
            "--doh" -> doh = harnessDohProvider(it.optionValue("--doh")) ?: run {
                System.err.println("Unknown DNS over HTTPS provider for --doh\n\n$TOOLS_USAGE")
                return 2
            }
            "--store" -> stores += it.optionValue("--store")
            "--packages" -> options = options.copy(
                packages = it.optionValue("--packages").split(',').map(String::trim).filter(String::isNotEmpty),
            )
            "--all" -> options = options.copy(all = true)
            "--lang" -> options = options.copy(lang = it.optionValue("--lang"))
            "--offline" -> options = options.copy(offline = true)
            "--apk" -> options = options.copy(forceApk = true)
            "--skip-nsfw" -> options = options.copy(skipNsfw = true)
            "--interactive" -> options = options.copy(interactive = true)
            "--wiring" -> wiring = true
            else -> {
                System.err.println("Unknown option $arg\n\n$TOOLS_USAGE")
                return 2
            }
        }
    }
    val runs = try {
        harnessRuns(options, stores)
    } catch (e: IllegalArgumentException) {
        System.err.println("${e.message}\n\n$TOOLS_USAGE")
        return 2
    }
    // A separate profile, so the harness never touches the user's library or trusted extensions
    val dirs = harnessDirs()
    val lock = lockHarness(dirs) ?: return 1
    try {
        return runHarness(dirs, runs, wiring, doh)
    } finally {
        lock.close()
    }
}

/** The provider number for a `--doh` name (the settings' providers, or `off`); null for an unknown name. */
internal fun harnessDohProvider(name: String): Int? = when (name.trim().lowercase()) {
    "off", "none" -> -1
    "cloudflare" -> PREF_DOH_CLOUDFLARE
    "google" -> PREF_DOH_GOOGLE
    "adguard" -> PREF_DOH_ADGUARD
    "quad9" -> PREF_DOH_QUAD9
    "alidns" -> PREF_DOH_ALIDNS
    "dnspod" -> PREF_DOH_DNSPOD
    "360" -> PREF_DOH_360
    "mullvad" -> PREF_DOH_MULLVAD
    "controld" -> PREF_DOH_CONTROLD
    "njalla" -> PREF_DOH_NJALLA
    else -> null
}

/**
 * One harness run per `--store` URL. The app names no store, so at least one URL is required. Each
 * run's report and trace files are named by [CompatibilityHarness.storeId] of its URL, made unique
 * (`owner`, `owner-2`, ...) so two stores of one owner or host never overwrite each other's files.
 * Over several stores each store tests only the `--packages` it lists, so a package of one store is
 * not a failure of another.
 */
internal fun harnessRuns(
    options: CompatibilityHarness.Options,
    stores: List<String>,
): List<CompatibilityHarness.Options> {
    val urls = stores.distinct()
    require(urls.isNotEmpty()) { "Give the store to test: --store <url>" }
    urls.firstOrNull { "://" !in it }?.let { throw IllegalArgumentException("Unknown store \"$it\": give its URL") }
    val used = mutableSetOf<String>()
    return urls.map { url ->
        val base = CompatibilityHarness.storeId(url)
        var id = base
        var n = 1
        while (!used.add(id)) id = "$base-${++n}"
        options.copy(storeUrl = url, storeId = id, skipMissing = urls.size > 1)
    }
}

/** The harness profile: outside the user's data folder, so its jars and browser profile do not roam. */
internal fun harnessDirs(): ZoukanDirs = ZoukanDirs.harness()

/** Takes the harness profile's lock; null (after saying so) when another harness run holds it. */
internal fun lockHarness(dirs: ZoukanDirs): AutoCloseable? =
    SingleInstance.acquireForCommand(dirs.root, "harness") ?: run {
        System.err.println("Another harness run is using ${dirs.root}; wait for it to finish.")
        null
    }

private fun runHarness(
    dirs: ZoukanDirs,
    runs: List<CompatibilityHarness.Options>,
    wiring: Boolean,
    doh: Int?,
): Int {
    ZoukanRuntime.start(dirs, interactiveBrowser = runs.any { it.interactive })
    // Before NetworkHelper and the browser read it. The harness has its own profile, so this never
    // changes the user's setting
    val dohPreference = Injekt.get<NetworkPreferences>().dohProvider()
    doh?.let(dohPreference::set)
    println("DNS over HTTPS: ${dohProviderUrl(dohPreference.get()) ?: "off (system DNS)"}")
    // NetworkHelper first, so the cookie store and Chromium hooks are in place
    Injekt.get<NetworkHelper>()
    // Every store runs, and each writes its own report, even when an earlier one failed
    val results = runs.map { options ->
        println("Store ${options.storeId}: ${options.storeUrl}")
        runCatching { runHarnessOn(dirs, options, wiring) }
            .onFailure { System.err.println("Store ${options.storeId} failed: ${CompatibilityHarness.describe(it)}") }
    }
    // Over several stores, a package that none of them lists is still a mistake
    val requested = runs.first().packages
    if (runs.size > 1 && requested.isNotEmpty()) {
        val found = results.flatMap { it.getOrNull()?.second.orEmpty() }.toSet()
        val missing = requested.map { it.removePrefix("eu.kanade.tachiyomi.extension.") }.filter { it !in found }
        if (missing.isNotEmpty()) {
            System.err.println("Not in any store: ${missing.joinToString()}")
            return 1
        }
    }
    return results.maxOf { result -> result.fold(onSuccess = { it.first }, onFailure = { 1 }) }
}

/** Runs one store; returns the exit code and the packages it tested. */
private fun runHarnessOn(
    dirs: ZoukanDirs,
    options: CompatibilityHarness.Options,
    wiring: Boolean,
): Pair<Int, List<String>> {
    if (wiring) {
        val check = ExtensionWiringCheck(
            dirs = dirs,
            storeService = Injekt.get<ExtensionStoreService>(),
            installer = Injekt.get<DesktopExtensionInstaller>(),
            loader = Injekt.get<DesktopExtensionLoader>(),
            options = ExtensionWiringCheck.Options(
                storeUrl = options.storeUrl,
                storeId = options.storeId,
                packages = options.packages,
                skipNsfw = options.skipNsfw,
                minExtensions = options.minExtensions,
            ),
        )
        val (report, rows) = check.run()
        report.save(check.reportFile())
        println("\nReport saved to ${check.reportFile()}")
        return wiringExitCode(rows) to rows.map { it.pkg }
    }
    val harness = CompatibilityHarness(
        dirs = dirs,
        storeService = Injekt.get<ExtensionStoreService>(),
        installer = Injekt.get<DesktopExtensionInstaller>(),
        loader = Injekt.get<DesktopExtensionLoader>(),
        options = options,
    )
    val (report, rows) = harness.run()
    val name = buildString {
        append("extensions-").append(options.storeId)
        if (options.forceApk) append("-apk")
        append("-").append(Platform.id)
    }
    val file = File(dirs.reports, "$name.md")
    report.save(file)
    println("\nReport saved to $file")
    return harnessExitCode(rows) to rows.map { it.pkg }
}

/** 1 when an extension failed to load or an app API it needs is missing; 0 for a clean run. */
internal fun wiringExitCode(rows: List<ExtensionWiringCheck.Row>): Int =
    if (ExtensionWiringCheck.appGaps(rows).isNotEmpty() || rows.any { !it.loaded }) 1 else 0

/**
 * 1 when an extension failed to install or load, or is not in the store. The live-site steps
 * (popular, details, pages) stay in the report only: they depend on the sites, not on the app.
 */
internal fun harnessExitCode(rows: List<CompatibilityHarness.Row>): Int =
    if (rows.any { !it.install.startsWith("ok") }) 1 else 0

private fun toolsBackup(args: List<String>): Int {
    val sub = args.firstOrNull()
    val rest = args.drop(1)
    if (rest.lastOrNull() == "--report") throw CliUsageError("--report needs a value")
    val flags = rest.filter { it.startsWith("--") }.toSet()
    val positional = rest.filterIndexed { i, a -> !a.startsWith("--") && rest.getOrNull(i - 1) != "--report" }
    return when (sub) {
        "check" -> {
            val file = positional.firstOrNull()?.let(::File) ?: return toolsUsage("backup check <file>")
            val result = BackupCheck.run(file, keepProfile = "--keep" in flags)
            val markdown = BackupCheck.markdown(result)
            println(markdown)
            rest.indexOf("--report").takeIf { it >= 0 }?.let { rest.getOrNull(it + 1) }?.let { path ->
                File(path).apply { absoluteFile.parentFile?.mkdirs() }.writeText(markdown)
                println("Report saved to $path")
            }
            if (result.passed) 0 else 1
        }
        "compare" -> {
            if (positional.size != 2) return toolsUsage("backup compare <a> <b>")
            val backups = DesktopBackups(File("."), stubSourceRepository = NoStubSources)
            val report = BackupComparison(backups.read(File(positional[0])), backups.read(File(positional[1]))).compare()
            println("Compared ${report.compared}")
            report.differences.forEach { println("- $it") }
            println(if (report.passed) "No differences" else "${report.differences.size} differences")
            if (report.passed) 0 else 1
        }
        "synthetic" -> {
            val file = positional.firstOrNull()?.let(::File) ?: return toolsUsage("backup synthetic <file>")
            if (file.exists() && "--force" !in flags) {
                System.err.println("$file exists; pass --force to replace it")
                return 1
            }
            val parent = file.absoluteFile.parentFile
            parent?.mkdirs()
            // Through a temporary file of its own: a failed write leaves the target alone, and no
            // other file of the folder is written or deleted
            val temp = Files.createTempFile(parent?.toPath(), "${file.name}.", ".tmp")
            try {
                temp.toFile().sink().use { BackupCodec.write(SyntheticBackup.create(legacy = "--legacy" in flags), it) }
                // Without --force, a file that appeared since the check above is not replaced either
                val options = if ("--force" in flags) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
                try {
                    Files.move(temp, file.toPath(), *options)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    System.err.println("$file exists; pass --force to replace it")
                    return 1
                }
            } finally {
                Files.deleteIfExists(temp)
            }
            println("Wrote $file (${file.length()} bytes)")
            0
        }
        else -> toolsUsage("backup check|compare|synthetic ...")
    }
}

private fun toolsUsage(text: String): Int {
    System.err.println("Usage: zoukan-tools $text")
    return 2
}
