# Testing the desktop app

This file explains the desktop test tiers, how to run them, and how to keep the coverage matrix
up to date. Run every command from the repository root. On Windows, use `gradlew.bat`.

## Tiers

| Tier | Gradle task | What it catches | When it runs |
| --- | --- | --- | --- |
| Unit | `:desktop:test` | Wrong logic: mapping, state, parsing, single composables. Also checks the coverage matrix (`CoverageMatrixTest`). | Every build, and on GitHub (`.github/workflows/ci.yml`): the whole tier (with `:extension-jvm:test`, `:domain:desktopTest`, `:android-compat:test`, `:backup:desktopTest`, `:data:desktopTest`, `:core:common:desktopTest` and `:source-api:desktopTest`, the shared modules' own tests) on Linux, Windows and macOS on every pull request and every push to master. On pull requests a separate Linux job, which does not gate, reports the coverage of the changed lines (see [Coverage](#coverage)). |
| Wiring | `:desktop:wiringTest` | Code that compiles but crashes when it runs: a missing Injekt registration (`InjektRegistrationsTest`), a screen or dialog that throws when it opens (`ScreenSmokeTest`). | Every build: `check`, and so `build`, depends on it. CI runs it under `xvfb-run -a` on Linux. |
| Button crawl | `:desktop:buttonCrawlTest` | A button that throws, logs an error, hangs the window or does nothing, and a button that appeared or went without the inventory being updated. Clicks every button of every check of the smoke test. | Not on GitHub. On Windows before each merge to master, in one unsharded run, by `:desktop:longChecks` (see [Before merging to master](#before-merging-to-master)). Not in `check`: it takes about half an hour in one JVM. |
| Journeys | `:desktop:journeyTest` | Broken user flows: browse, read, download, back up, change source settings. Runs offline against a local fixture server. | On Windows before each merge to master, by `:desktop:longChecks`. Also in `:desktop:nightlyCheck`, which `.github/workflows/nightly.yml` runs every night, and on Windows on pull requests that change downloads, the browser, the reader, backup or the storage folder (`.github/workflows/journeys.yml`, see [On GitHub](#on-github)). |
| Extension wiring | `:desktop:extensionWiringTest` (with the journeys: `:desktop:nightlyCheck`) | Downloads every extension of the Keiyoushi store and of yuzono's "Cursed" store and checks it loads on the desktop. | On Windows before each merge to master, by `:desktop:longChecks`; on that machine every night too if `desktop/packaging/check/nightly-windows.ps1 -Register` was run. On GitHub only with the journeys, in `:desktop:nightlyCheck`, which `.github/workflows/nightly.yml` runs every night (see [On GitHub](#on-github)). |
| Android compile and unit tests | `:app:compileDebugKotlin` and `compileDebugKotlinAndroid` of `:data`, `:domain`, `:core:common`, `:source-api`, then `:app:testDebugUnitTest` and `:domain:testDebugUnitTest` | A desktop change to shared Android code (`app/.../data/track/`, `data/`, `domain/`) that breaks the Android build or the Android app's unit tests (they run on the JVM, not on a device). | Every pull request and every push to master: the "Android compile and unit tests" job in `.github/workflows/ci.yml`, described in the comment above it. Locally it needs `ANDROID_HOME` set to an Android SDK. |
| Live | `:desktop:test -Pzoukan.e2e=true` | The `EndToEnd*Test` classes. Most of them use real sites and need the network. | When you ask for them, and every night on GitHub (`EndToEndReadTest` and `EndToEndDownloadTest`, in `.github/workflows/nightly.yml`). Never in the PR gate. |

Use the cheapest tier that can catch the bug:

- Put logic in a unit test. Most tests belong here. They are fast and give clear failures. They
  live in `desktop/src/test/kotlin/zoukan/desktop/`, in the same package as the code.
- Rely on the wiring tier to show that a screen or dialog opens. You rarely write a new test for
  it: the smoke test finds new screens and pages by itself (see [The smoke test](#the-smoke-test)).
- Write a journey only for a flow that crosses screens and needs the real UI, such as "add to
  library, then read". Do not test logic through a journey that a unit test could test.
- Use a live test only when the behaviour depends on a real site. Live tests do not count as
  coverage, because the PR gate never runs them.

`DownloadManager.kt` and `KcefWebViewProvider.kt` are too large to reason about a change by
reading alone. Before you change either file, first write tests that pin the current behaviour of
the part you will change, and see them pass on the unchanged code. Then make the change.

## The release gate

The release gate on GitHub (`ci.yml`, which `desktop-build.yml` runs before it publishes) is the
fast set only: Spotless, the build and unit tests, the wiring shards, the unit tests on Windows and
macOS and the Android compile and unit tests. Its jobs run side by side; the Windows and macOS
runners are the slowest, so they set how long the gate takes.

Every pull request and every push to master (and so every release, since `desktop-build.yml`
calls `ci.yml`) runs the whole unit tier on Windows and macOS as well as on Linux: `:desktop:test`,
`:extension-jvm:test`, `:domain:desktopTest`, `:android-compat:test`, `:backup:desktopTest`,
`:data:desktopTest`, `:core:common:desktopTest` and `:source-api:desktopTest`.
A test that fails only on Windows or macOS (file names, links and junctions, the secret stores,
the macOS marks) therefore fails the pull request, not the first master run after the merge. The
"PR gate" job needs all of these jobs. Tests for one system skip themselves on the others with
`assumeTrue`.

### On GitHub

- `.github/workflows/nightly.yml` runs every night at 03:17 UTC on master, in the main repository
  only (forks skip it), and opens, updates or closes the `nightly-failure` issue. A run started by
  hand reports to the issue only when its `notify` box is ticked. It does not gate a merge. It
  runs, on Windows and macOS, the build and unit tests, `:desktop:nightlyCheck` (the journeys and
  the extension wiring check) and the live `EndToEndReadTest` and `EndToEndDownloadTest`, and on
  Linux (ubuntu-24.04) a comparison of the screenshots with the committed goldens.
- `.github/workflows/journeys.yml` runs `:desktop:journeyTest` on a Windows runner for pull
  requests that change `desktop/src/main/kotlin/zoukan/desktop/` `download/`, `browser/`,
  `ui/reader/`, `ui/downloads/`, `backup/` or `storage/`, the shared `backup/` or `core/archive/`
  modules, or the journeys themselves. Other pull requests do not start it. It is not part of the PR
  gate: do not make it a required check, since most pull requests never run it.

## Before merging to master

The long tiers, the journeys, the button crawl and the extension wiring check, do not gate a
merge on GitHub: the nightly runs the journeys and the extension wiring check after the merge,
`journeys.yml` runs the journeys only on some pull requests, and nothing on GitHub runs the button
crawl (see [On GitHub](#on-github)). They must pass on Windows before a pull request is merged to master (and so before
it is released). On a Windows machine, in a checkout of the pull request's branch:

```powershell
.\gradlew.bat :desktop:longChecks --continue
```

It runs `journeyTest` (up to 4 journeys side by side), then the whole button crawl in one JVM,
then `extensionWiringTest` (needs the network), one after the other: well over an hour. It refuses `-Pzoukan.shard`. Reports go to `desktop/build/reports/journeys/`,
`desktop/build/reports/coverage/buttons.md` and `desktop/build/reports/e2e/`.

The three checks can also run side by side, each in its own worktree of the branch, to save time:

```powershell
.\gradlew.bat :desktop:journeyTest --continue
.\gradlew.bat :desktop:buttonCrawlTest
.\gradlew.bat :desktop:extensionWiringTest --continue
```

### The record of the long checks

Each long check that passes leaves a record in `desktop/build/long-checks/`: `journeyTest`,
`buttonCrawlTest` and `extensionWiringTest` each write `<check>.properties`, and `longChecks`
writes `longChecks.properties` when all three passed. A record names the commit
(`git rev-parse HEAD`), whether the working tree had uncommitted changes, the date and the tasks
run. A check writes no record when it was narrowed (`--tests`, `-Pzoukan.shard`,
`-Pzoukan.crawl.only`, `-Pzoukan.crawl.updateBaseline`, `-Pzoukan.e2e.package`, or only some
stores with `-Pzoukan.e2e.stores`), or when the commit or the working tree changed while it ran.
A check that starts deletes its old record, so a failed run leaves none.

Before pushing to master, check that the records name the commit you push:

```bash
./gradlew :desktop:verifyLongChecks
./gradlew :desktop:verifyLongChecks -Pzoukan.longChecks.records=../zoukan-journeys/desktop/build/long-checks,../zoukan-crawl/desktop/build/long-checks,../zoukan-wiring/desktop/build/long-checks
```

It fails, and says why, unless the working tree is clean and there is a `longChecks` record, or a
record of each of the three checks, for the current HEAD with a clean tree. It always reads
`desktop/build/long-checks/`; `-Pzoukan.longChecks.records` adds record files or folders
(comma-separated, relative to the repository root), such as the record folders of the worktrees the
checks ran in, or records copied from a Windows machine. A rebase changes the commit, so run the
checks again after one.

## Commands

```bash
./gradlew spotlessCheck :desktop:build --continue    # before every pull request: unit and wiring tiers
./gradlew :desktop:test                              # unit tests
./gradlew :desktop:test --tests "*CoverageMatrix*"   # one class, or a pattern
./gradlew :desktop:wiringTest                        # Injekt registrations and the smoke test
./gradlew :desktop:wiringTest --tests "*ScreenSmokeTest" -Pzoukan.smoke.only="tab:Library"
./gradlew :desktop:wiringTest --tests "*ScreenSmokeSmallWindowTest" -Pzoukan.smoke.only="settings:"   # at 800x560
./gradlew :desktop:journeyTest                       # every offline journey
./gradlew :desktop:buttonCrawlTest                   # click every button (long: about half an hour)
./gradlew :desktop:buttonCrawlTest -Pzoukan.crawl.only="settings:Library"
./gradlew :desktop:buttonCrawlTest -Pzoukan.shard=2/4                     # the second of four shards (to debug)
./gradlew :desktop:buttonCrawlTest -Pzoukan.crawl.updateBaseline=true     # rewrite button-inventory.txt
./gradlew :desktop:journeyTest --tests "*BrowseAndReadJourneyTest"
./gradlew :desktop:nightlyCheck --continue           # journeys and every extension
./gradlew :desktop:longChecks --continue             # before merging to master: journeys, crawl, every extension
./gradlew :desktop:verifyLongChecks                  # before pushing to master: the long checks passed on HEAD
```

Notes:

- `:desktop:test` leaves out the wiring tests and the journeys. They start the app's runtime,
  which can start only once per JVM, so their own tasks run them.
- The wiring tests, the journeys and the crawl open real windows, so they need a display. On Linux
  without one, run Gradle under `xvfb-run -a`, as CI does for the wiring tests.
- `-Pzoukan.smoke.only` is a regular expression. It runs only the smoke checks whose name or
  coverage-matrix key matches it, for example `"tab:Library"`, `"browse:"` or `"dialog new category"`.
  Without `--tests`, `wiringTest` runs `InjektRegistrationsTest` and both smoke classes:
  `ScreenSmokeTest` at 1280x820 and `ScreenSmokeSmallWindowTest` at the app's minimum window size
  (800x560), in two JVMs side by side. Each smoke check runs the usability rules in the light and
  the dark theme, and a failure names the window size, for example `[800x560] tab Library`.
- Gradle can take `:desktop:test` from its build cache. Add `--rerun` to run the tests again
  anyway.
- `journeyTest` and the `extensionWiringTest` tasks never count as up to date. They always run.
- Never run two `journeyTest` builds at the same time in one checkout. They share the build
  folder and the screenshot folder.
- Each journey runs in a JVM of its own.

## Coverage

[Kover](https://github.com/Kotlin/kotlinx-kover) measures which lines the unit tier runs. The
`zoukan.kover` convention (`buildSrc`) applies it to `desktop`, `extension-jvm`, `android-compat`,
`backup`, `data`, `core:common` and `source-api` (the modules with tests) and to `domain` (no
desktop tests of its own, but the desktop tests run its desktop code). The root project
aggregates them into one report. There is no threshold: most of the code comes from upstream.
`android-compat`, `backup`, `data`, `core:common` and `source-api` have their own tests
(`android-compat/src/test`, `<module>/src/desktopTest`) for the project's own code in them; the
desktop tests cover the rest from outside.

```bash
./gradlew :koverXmlReportUnit                       # every module: build/reports/kover/reportUnit.xml
./gradlew :koverHtmlReportUnit                      # the same as HTML, to browse
./gradlew :backup:koverXmlReportUnit                # one module: backup/build/reports/kover/reportUnit.xml
./gradlew :desktop:test --tests "zoukan.desktop.backup.*" :desktop:koverXmlReportUnit   # a few tests
```

- Each module's `unit` report has its JVM classes (a multiplatform module: its desktop target's),
  measured by its `test` or `desktopTest` task. The Android variants and the Android unit tests
  are left out, and so are the other Test tasks (`wiringTest`, `journeyTest`, `buttonCrawlTest`,
  the `extensionWiringTest` tasks): they are never instrumented, and a report does not run them.
- The tests are instrumented only when the command line asks for a Kover task, or with
  `-Pzoukan.coverage=true`, so an ordinary `test` run is not slowed down. Switching between the
  two makes the tests run again, since the instrumentation is one of their inputs.
- Use the `unit` reports (`koverXmlReportUnit`). Kover's default reports (`koverXmlReport`) also
  take the Android variants of the multiplatform modules, which compiles them and needs
  `ANDROID_HOME`.

On pull requests, the "Coverage (information only)" job in `ci.yml` (on Linux) runs
`:koverXmlReportUnit -Pzoukan.coverage=true`, which runs the unit tier again with the coverage
agent, and writes the coverage of the lines the pull request changes to the job summary: per file, how many changed executable lines the tests ran and which
they did not. `.github/scripts/changed_line_coverage.py` makes it from `git diff --unified=0
<base>...HEAD` and the report. Only the project's own lines count: in `android-compat` the lines
inside ZOUKAN markers; elsewhere every line, so upstream files show as they are. It is
information only: the PR gate does not need the job and it never fails the run, but a step that
breaks shows as failed, and a report that cannot be made (a failing test stops it, or the diff
with the base branch is missing) is said as a warning in the job summary. The gating "Build and
unit tests" job never runs instrumented, on pull requests or on master, so the tests that gate a
merge run the same way every time and reuse the build cache; it does run the script's own tests
(below). To see the report for your branch, and to test the script after you change it:

```bash
./gradlew :koverXmlReportUnit
python3 .github/scripts/changed_line_coverage.py --base origin/master --report build/reports/kover/reportUnit.xml
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
```

Later, try mutation testing on the hotspots, to find assertions that are too weak: `PagedViewer.kt`,
`ZoukanApp.kt`, `SettingsWidgets.kt`, `DownloadManager.kt` and `KcefWebViewProvider.kt`. It is not
set up yet.

## The home folder and file-system side effects

Every desktop test task gives its JVMs a home folder and a data folder of their own under
`<module>/build/test-home/<task>` (`user.home`, `zoukan.dataDir`), so no test touches the real
home folder or Zoukan profile: every Test task of `desktop`, `extension-jvm` and `android-compat`,
and the `desktopTest` task of `backup`, `domain`, `data`, `core:common` and `source-api` (the
`zoukan.test-home` convention in `buildSrc`). The Android unit test tasks are left as they are. Test code reads the home folder only
through `zoukan.desktop.testing.TestHome`; `HomeFolderScanTest` fails on any other read.

A test that starts a JVM passes the same folders on: `TestHome.childJvmArguments()` (or
`TestHome.javaCommand(argv)`) for `java` and the app's `MainKt`, `TestHome.jdkToolArguments()` for
`keytool`, `jarsigner` and `jcmd`. A child JVM the app itself starts (the Spotlight helper, from
`SpotlightExclusion`) gets no `-Duser.home`, so in a test it would still use the real home (JNA's
native cache, for one); the tests that start it for real are opt-in (`ZOUKAN_TEST_REAL_SPOTLIGHT`).

A test of code that creates, deletes, marks or changes the permissions of files uses
`zoukan.desktop.testing.FileSystemSandbox`: a temporary home with the app's data and storage
folders, a folder the user chose, a link out of the app's folders, another program's crash files
and a read-only folder. `assertNothingOutsideAppChanged()` fails, listing each change, when a file
outside the app's folders was created, deleted or changed (content, permissions, extended
attributes, link targets).

## Waiting in tests

Tests wait for the state they need, never for a guessed time: `zoukan.desktop.testing.Await`
(`until`, `untilSuspending`, `within`), a `CountDownLatch` or `CompletableDeferred`,
`StateFlow.first { }` under `withTimeout`, Compose's `waitUntil` (in journeys `waitFor` and
`waitOrDump`), or virtual time with `runTest` (in a Compose test, `mainClock`: an effect's `delay`
runs on it). A check that something does not happen waits for the point after which it cannot
happen any more, never for a while:

- in journeys, `awaitAppIdle()`: the UI, the user actions (`launchAction`, which `launchReporting`
  uses; `zoukan.desktop.ui.common.actionsRunning` counts them), AppScope's work and the download
  manager's have ended, so a file still there or a request not sent is final. An action a check
  like this guards starts with `launchAction`, on AppScope or in the download manager; work that
  lasts the whole run goes on `ServiceScope`;
- a lock or semaphore does not show who waits for it: the test puts in one that records each
  request (the download manager's `entryLocks`, the extension manager's `locks`, `AddToLibrary.fetches`)
  and checks the state once the request is made, while it holds what is asked for;
- a clock the code reads is a parameter (`DownloadManager`'s `indexClock`), which the test moves;
- what the garbage collector keeps: a weak reference that survives a full collection, which a
  canary weak reference shows has happened.

The sleeps that stay are where time itself is the subject: the wait helper's own polling, a fake's
planned slowness (the fixture and test servers), a live test by hand against real Chromium, the
manual end-to-end runs' pauses before a screenshot, and the button crawl's `measure()` and
`SmokeChecks.settle()`, which are left as they are so the crawl's signatures and baselines do not
move.
`ThreadSleepScanTest` (in `zoukan.desktop.scan`) fails on `Thread.sleep` (and `TimeUnit.*.sleep`)
in any test source set, and on a `delay` that pauses a `runBlocking` body, outside its short
allow-list, in which every entry has a reason. It also sees the block of a test helper that takes a
`suspend` lambda and runs it under `runBlocking` (such as `withStubbedSignatures { }`) as such a
body. It reads the text, so it does not see a `delay` in a block that reaches `runBlocking` another
way: a wrapper that does not take the block as a `suspend` lambda parameter of its own (a class, a
stored lambda), or a library function's lambda inside a `runBlocking` body (`mockkObject(...) { }`),
which reads as any other lambda.

The scan tests' allow-lists (`zoukan.desktop.scan`) name each site: an entry gives the file, a
part of the line and, where a file has more than one site, the function the site is in, and it must
match exactly its count of sites (one by default). A new site in an allowed file fails until it has
an entry and a reason of its own; a fixed site fails until its entry goes.

## Fault injection

The failure tests make a fault happen for real, or through a seam with today's behaviour as its
default, and check three things: the user sees an error (`zoukan.desktop.testing.UserMessages`), the
app keeps going and a retry after the fault is healed works, and nothing is lost or half written.
The kit is in `zoukan.desktop.testing.fakes`: `FileFaults` (a read-only folder, a file where a
folder goes), `WriteFaults` (a full disk at the start or part way through a file, a close that
fails, for code that opens its files through a seam), `FaultyFileSystem` (okio), `FailingSqlDriver`,
`FailingSecretStore`, and `FixtureServer`'s failure kinds (`Drop`, `Truncate`, `Stall`, `Corrupt`,
`WrongType`, `Oversize`, `Answer`). A stall test gives the client under test a short timeout (the
seam is the client: a source's own, `NetworkHelper.plainClient`, the installer's and the release
feed's); the app's own timeouts are 10 to 30 s.

| Feature | Files | Database | Network (incl. stall) |
| --- | --- | --- | --- |
| Download | `DownloadFaultsTest`: read-only folder, file in the way, full disk at the start, in a page and in the archive, close and rename failing (`newDownloader` seam) | `DownloadDatabaseFaultsTest` (automatic deletion, delete, the queue restore: a failed read keeps the downloads not yet restored saved); ComicInfo's categories are optional | `DownloadFaultsTest`: status, cut, drop, HTML, stall |
| Backup (Settings, automatic) | `BackupFaultsTest`: read-only folder, file in the way, full disk at the start and part way, close and rename failing (`openSink`/`move` seams); rotation does not run after a failure | `BackupFaultsTest` | does not apply |
| Restore | `RestoreFaultsTest`: bad files; `edits.json` in a read-only folder or with a folder in the way. Not covered: the source settings files, which the settings layer writes in the background | `RestoreFaultsTest`, `RestorePartialFailureTest` | does not apply |
| Sync | does not apply: sync writes no file of its own; its safety backup failing: `SafetySnapshotTest`, `SyncDatabaseFaultsTest` | `SyncDatabaseFaultsTest` (`SyncManager.syncData` end to end, WebDAV) | `SyncFaultsTest`: status, cut, drop, corrupt, stall (WebDAV and SyncYomi) |
| Extension install | `ExtensionInstallFaultsTest` | does not apply | `ExtensionInstallFaultsTest`, stall included |
| Update | `UpdateFaultsTest` | does not apply | `UpdateFaultsTest` |

## Old databases, the Android storage folder and backups

- `DatabaseMigrationTest` (in `zoukan.desktop.db`) opens old databases through the desktop's own
  start-up path and checks every row, field by field, the schema against a new install's and
  `user_version`. `OldDatabases` builds them, encrypted, from SQL text and the generated
  migrations, so no binary is committed: from version 28 on, from the schema snapshot
  `data/src/commonMain/sqldelight/28.db`; before it, from the version-1 schema in
  `src/test/resources/db/schema-v1.sql`. That file is TachiyomiSY's first database (the table SQL
  its pre-SQLDelight code used at database version 1, from its public history; `1.sqm` to
  `13.sqm` repeat that code's upgrades), and a test proves it: running `1.sqm` to `27.sqm` on it
  gives `28.db`'s schema and rows exactly, apart from what `14.sqm` leaves behind (below).
  Versions opened through the start-up path: 27, 28, 38 and the one before the newest; 4 and 14
  are first migrated to 27 by `OldDatabases`, since the start-up path cannot take them (below).
  Every version from 1 is built and seeded. `MigrationSeed` holds the known rows and what each
  migration does to them. A new migration that adds a table or a column fails these tests until
  `MigrationSeed` has rows for the table, or says what old rows get in the column.
  Only the newest version occurs in the field: the desktop's first release already had it, and data
  from Android arrives in backups, not database files. The older versions are tested for
  completeness, since the migrations are shared with the Android app. Pinned as they are: a
  database from before 4 fails at `13.sqm` (`3.sqm` names a column `COL_MANGA_SOURCE`); before
  15, a row whose parent is gone fails `13.sqm` or `14.sqm` (the desktop migrates with foreign
  keys on); before 27, the start-up path fails at `26.sqm`, whose table rename makes SQLite check
  `20.sqm`'s trigger and its double-quoted string (the trigger is rewritten only after migrating),
  and past that, two favourites with the same gallery fail `26.sqm`. Each failure leaves the
  database and the copy made before the migration as they were. A database that went through
  `14.sqm` keeps `history.last_read` NOT NULL and a stale `history_temp` table, unlike a new
  install.
- `AndroidStorageFolderTest` (in `zoukan.desktop.storage`) writes a storage folder as the Android
  app leaves it (`AndroidStorageFolder`: downloads as folders and CBZs, a local source entry,
  automatic backups, `.nomedia` files), reads it with the app's storage code, and fails when a file
  from Android is changed or deleted, or a file is added anywhere but the expected places.
- `BackupRoundTripTest` restores and backs up again `FullFieldBackup` (every field of the format
  set; `SyntheticBackupCoverageTest` checks that against the model classes) and every `.tachibk` in
  `src/test/resources/backups/`. To test a backup from another Android version, add the file there.

## The secret stores

`SecretStoreContract` (in `zoukan.desktop.secrets`) is what every `SecretStore` must do; each store
is a small subclass. The file store and the in-memory fakes run it everywhere. On macOS and Windows,
`MacKeychainStoreContractTest` and `WindowsCredentialStoreContractTest` run it against the real
Keychain and Credential Manager as part of `:desktop:test`: every key starts with
`zoukan-contract-test-` and a random part, is never one of the app's keys, and is deleted after
each case. They skip themselves on other systems, when the environment variable
`ZOUKAN_SKIP_NATIVE_SECRET_TESTS=true` is set, on a locked login keychain, and in a Windows logon
with no Credential Manager, such as SSH (error 1312).

The Keychain tests are opt-in: they run only with `ZOUKAN_KEYCHAIN_TESTS=true` (CI's macOS runner
sets it). So that a test never waits on a prompt, they turn Keychain user interaction off
(`SecKeychainSetUserInteractionAllowed`) while they run, and Apple does not document that setting
as the test process's own: its page for the function warns that leaving it off affects other
clients of Keychain Services. Turn them on by hand only where that does not matter:

```sh
ZOUKAN_KEYCHAIN_TESTS=true ./gradlew :desktop:test --tests "*MacKeychainStoreContractTest" --rerun
```

(`--rerun`: the switch is not an input of the task, so a run that skipped them would be up to date.) `SecretStoreFailureTest` uses
`zoukan.desktop.testing.fakes.FailingSecretStore`, a store that is locked, denied or unavailable on
demand, to check that a failing store never opens, rewrites or re-keys an existing database.

## The coverage matrix

`desktop/src/test/resources/coverage-matrix.yaml` lists every user-facing feature and the tests
that cover it. `CoverageMatrixTest` checks it in the unit tier. It does not start the app.

### Keys

Each entry has a key. The key says what kind of thing the entry is and names it:

| Key | What it names | Example |
| --- | --- | --- |
| `screen:<package>.<Class>` | A Voyager screen. The package is relative to `zoukan.desktop.ui`. | `screen:browse.SourceScreen` |
| `tab:<HomeTab>` | A home tab. | `tab:Library` |
| `browse:<BrowsePage>` | A page of the Browse tab. | `browse:MIGRATE` |
| `settings:<SettingsCategory>` | A Settings page. | `settings:Downloads` |
| `dialog:<package>.<function>` | A dialog in a function named `...Dialog`, `...Dialogs` or `...Sheet`. | `dialog:manga.CoverDialog` |
| `dialog-inline:<File>.kt:<function>` | An `AlertDialog(` or `Dialog(` written inline in another function. | `dialog-inline:SourceScreen.kt:FilterPanel` |
| `state:<name>` | An app state or a dialog that the test cannot find by itself. | `state:app-lock` |
| `flow:<area>.<name>` | An action inside a screen. | `flow:library.selection` |

The test finds the `screen:`, `tab:`, `browse:`, `settings:`, `dialog:` and `dialog-inline:` keys
in the compiled app. Screens come from a class scan, tabs and pages from their enums, and named
dialogs by reflection. You write the `state:` and `flow:` keys by hand. The test cannot find
them, so it does not ask for them. `settings:search` is also written by hand. Each run of
`CoverageMatrixTest` writes what it found to `desktop/build/reports/coverage/discovered.txt`. Gradle
skips the test when nothing changed, so add `--rerun` to get the file.

For the three enums, the key uses the enum entry's name exactly as written in the code:
`HomeTab` (`ui/ZoukanApp.kt`), `BrowsePage` (`ui/browse/BrowseTab.kt`) and `SettingsCategory`
(`ui/settings/SettingsTab.kt`), all under `desktop/src/main/kotlin/zoukan/desktop/`.

### Fields

```yaml
- key: dialog:manga.CoverDialog
  feature: "Cover dialog"
  tier: smoke
  smoke: true
  tests: [ui.ScreenSmokeTest]
```

- `key`: see above. Each key appears once.
- `feature`: what the user sees, in a few words.
- `tier`: one of `unit`, `smoke`, `journey`, `nightly` or `manual`. Use the cheapest UI tier that
  covers the key today (`smoke`, then `journey`). Use `unit` when only unit tests cover it. With an
  empty `tests` list, use the tier the missing test will have.
- `smoke`: `true` if `ScreenSmokeTest` has a check with this key. The smoke test fails if a row
  says `true` and no check has the key.
- `tests`: test classes under `zoukan.desktop`, such as `ui.ScreenSmokeTest` or
  `e2e.DownloadJourneyTest`. Each one must exist and hold at least one `@Test`. List a test only
  if breaking the key's feature makes it fail. A test of related logic goes in `gap:`, not here.
  A test method can be named with `#` (`ui.Foo#bar`) only when its name has no spaces, so for the
  usual backticked names list the class. Do not list the live `EndToEnd*Test` classes: CI never
  runs them.
- `gap` (optional): why nothing covers the key, or what is still not covered. It is required
  when `tests` is `[]`, and when `tier` is `smoke` but `smoke` is `false`. Name a planned test by
  its class (`planned: FooJourneyTest`), not by a planning document or ID.

The file uses a small part of YAML, read line by line. A value that holds `": "` or `" #"`, or
starts with `[`, `{`, `'` and similar characters, must be in double quotes. So must a value that
YAML reads as null or a boolean, such as `null`, `~`, `yes` or `no`. `tests` is always a list such
as `[a, b]` or `[]`.

### Adding a row

A new screen, tab, Browse page, Settings page or named dialog makes `CoverageMatrixTest` fail and
name the key:

```
java.lang.AssertionError: 1 coverage problem(s):
- tab:History has no row in coverage-matrix.yaml; add one (desktop/TESTING.md, 'Adding a row')
```

Add an entry with that key and the test that covers it. If no test covers it yet, leave `tests`
empty and give the reason in `gap:`.

The test lists every problem, one per line. The other messages:

- A row whose screen, page or dialog was renamed or removed:

  ```
  - screen:nope.Gone (coverage-matrix.yaml line 1244) names a screen, page or dialog that no longer exists; rename the row to the new key or remove it
  ```

- A test name that does not exist:

  ```
  - tab:History (line 304): test class zoukan.desktop.ui.NoSuchTest does not exist; fix the name in tests: or remove it
  ```

- Also: a key that appears twice, a key with an unknown prefix (see [Keys](#keys)), a tier that is
  not in the list, a row with no tests and no gap, a `smoke` tier row with `smoke: false` and no
  gap, and a planning folder or ID in `feature` or `gap`.

For a new action inside a screen, add a `flow:` row by hand. Nothing forces you to, so do it as
part of the change.

### New dialogs

Put a new dialog in its own top-level composable function whose name ends in `Dialog`, `Dialogs`
or `Sheet`. Reflection then finds it and the matrix asks for its row. A dialog in a member
function is not found, and counts as inline.

An `AlertDialog(` or `Dialog(` call inside any other function is an inline dialog. The test
cannot see what it is, so it is held to a baseline: `desktop/src/test/resources/coverage-inline-dialogs.txt`.
The baseline counts the sites in each function: a line is `<key>` for one site or `<key> <count>`
for more. The function is the innermost `fun` whose body is open at the call. The counts may only
shrink. A new inline dialog, also one in a function that already has a line, fails the second
test, `inline dialogs stay within the baseline`:

```
java.lang.AssertionError: 1 coverage problem(s):
- new inline dialog in dialog-inline:ScratchBreak.kt:ScratchPanel (1 sites, the baseline allows 0): extract it into a fun ...Dialog, so the coverage matrix sees it
```

When you extract an old inline dialog into a `...Dialog` function, lower its count or remove its
line. The test fails until you do. Inline dialogs need a baseline line, not a matrix row, but
they may have a row.

### Gaps

A `gap:` says what is not covered and why. Some gaps are manual for good: the system tray, the
window's size and position, and the operating system's reduced-motion setting cannot be driven
from a Compose test. Other gaps name the test that will cover them. To find the open gaps, search
the matrix for `tests: []` and `gap:`.

## Android and contract checks

The unit tier also reads the Android sources and the contract documents
(`desktop/src/test/kotlin/zoukan/desktop/contract/`). They read source text with regexes, start
nothing, and fail when they find suspiciously few entries, so a broken regex cannot pass.

| Test | What it checks | When it fails |
| --- | --- | --- |
| `PreferenceParityTest` | Each preference key the desktop's own code declares has the Android key and default | Use Android's key and default, name a desktop-only key `zoukan_...`, or list the difference in `PARITY.md` (the key in backticks) or in the test's known differences, with the reason |
| `StorageNamesParityTest` | The folder and file names in the storage folder (downloads, local source, automatic backups) equal the Android ones | Use the Android name, or record the difference in the test |
| `ParityReferencesTest` | Every file path and symbol in backticks in a `PARITY.md` row still exists | Update the row to the new name, or remove it |
| `ThreatModelCoverageTest` | Every trust boundary in `THREAT-MODEL.md` has a row in its "Checked by" table, and every test class named there exists | Add the row, fix the class name, or say what is not covered in the Gap cell |

The preference classes the desktop takes from the shared modules or copies with the app-shared
sync are Android's own code, so the preference test does not compare them. Known differences
marked `reviewed = false` were found when the tests were written and wait for a decision: a bug
to fix, or a difference to document in `PARITY.md`.

## Property tests

`zoukan.desktop.property` checks rules for any input, with Kotest's property testing run from
ordinary JUnit tests: download folder and file names are valid on Windows, macOS and Linux and
equal the Android app's wherever Android's name is valid too (otherwise Android's name is among the
names looked up); any backup survives a write and a read; any preference survives a save
and a reload, plain and encrypted; and the parsers of outside data (`ComicInfo.xml`, an extension
store's index, the update manifest, a backup file) give a result or the error their caller handles
for any input, in bounded time. The Android name code they compare with is copied in
`AndroidNameOracle.kt`; `AndroidNameOracleSourceTest` fails when the Android source changes.

Each property runs a few hundred tries with a fixed seed, so a run is repeatable; together they
take a few seconds. To repeat a failure with another seed or search longer:

```sh
./gradlew :desktop:test --tests "zoukan.desktop.property.*" -Pzoukan.property.seed=42 -Pzoukan.property.iterations=20000
```

A defect a property finds gets a disabled test with its smallest input (the `*KnownDefectsTest`
classes), and only that case is left out of the property, with a comment naming the test. Enable
the test when the defect is fixed, and put the case back in the property; the test stays as a
regression test. Every defect found so far is fixed, so none of these tests is disabled now.

## The smoke test

`ScreenSmokeTest` (`desktop/src/test/kotlin/zoukan/desktop/ui/ScreenSmokeTest.kt`) starts the app once, offline, with a throwaway data folder. It seeds a library
with two local entries, chapters and one category. Then it opens, one check at a time:

- every Voyager screen of the app, found by the same class scan as the matrix;
- every home tab, and every Browse page, opened by its tab's test tag `browse-page-<BrowsePage>`, which must then be selected;
- every Settings page, and Settings search;
- the dialogs that other code can call, rendered directly (see `dialogs()`);
- some dialogs private to a screen, opened through the UI (see `uiDialogs()`), and the app lock
  and first-run setup.

On each screen it scrolls every list to its end, so every row composes. A check fails if anything
throws: in composition, in an effect, in a screen model, or on any other thread. The run also
fails if the log holds Injekt's "No registered instance or factory".

Each check has a `key`, its coverage-matrix key. Variants of one screen share a key. Before any
check runs, the test makes three assertions. Each one lists every key that breaks it:

- Every screen, tab, Browse page and Settings page has a check. `HomeScreen` is the exception:
  every check renders it. If not, it fails and names the key:

  ```
  java.lang.IllegalStateException: screens, tabs or pages that no smoke check opens:
    tab:History
  ```

- Every matrix row with `smoke: true` has a check with that key, or a check that lists the key in
  `alsoCovers` (for something a check opens on the way, such as a dialog its `drive` waits for).
- Every check's key has a matrix row.

These run before the `-Pzoukan.smoke.only` filter, so a filtered run still checks them.

### Adding a screen

A new screen needs no change if it is an `object` or has a constructor without arguments. If its
constructor takes arguments, add it to `screensWithArguments`, with one entry per variant:

```kotlin
MangaScreen::class.java to listOf({ MangaScreen(f.alpha.id) }),
```

Until you do, the test fails and names the class. A new tab, Browse page or Settings page is an
enum entry, and the smoke test opens it without a change. `BrowseTab` gives each page's tab the
test tag `browse-page-<NAME>`; keep that tag, or the page's check fails.

### Adding a Settings page

1. Add an entry to `SettingsCategory`. Write unit tests for any logic behind the page.
2. Run `./gradlew :desktop:test`. `CoverageMatrixTest` fails and names `settings:<Entry>`.
3. Add its row: `tier: smoke`, `smoke: true`, `tests: [ui.ScreenSmokeTest]`, plus any unit tests.
4. The smoke test opens the page without a change: it goes through every `SettingsCategory`
   entry. A Settings page needs no test tag. Run its check:
   `./gradlew :desktop:wiringTest --tests "*ScreenSmokeTest" -Pzoukan.smoke.only="settings:<Entry>"`.
5. Put each dialog of the page in a `...Dialog` function, and add its row and smoke check (see
   below).

A page that shows its rows only in some state needs its own check. `settings()` has an example:
the MangaDex page installs an offline MangaDex stand-in while its check runs.

### Adding a dialog

Add a check to `dialogs()` with the dialog's matrix key:

```kotlin
dialog("dialog:manga.CoverDialog", "CoverDialog") { CoverDialog(f.alpha, noop) },
```

Then set `smoke: true` on its matrix row. For a dialog that only opens through the UI, add a
`Check` to `uiDialogs()` with a `drive` block that clicks to it. Undo any state you change in
`cleanup`, or later checks will see it.

### Reading a failure

The output starts with `Smoke: <n> screens, <n> checks`, then prints `Smoke: <check name>` as each
check opens. A failed check prints `Smoke: FAILED in <check> (<where>): <error>`, the stack trace,
and then the semantics tree that was on screen. Use the tree to tell a crash from a test that
looked for the wrong node. For example, if the `browse MIGRATE` tab loses its test tag, the check
fails with a `ComposeTimeoutException` after 10 seconds, and the tree shows the Browse tab without
that tag. The other checks still run.

To run one check again, filter by its key or name:

```bash
./gradlew :desktop:wiringTest --tests "*ScreenSmokeTest" -Pzoukan.smoke.only="browse:MIGRATE"
```

A filter that matches nothing fails and lists every check's key and name.

## Journeys

The journeys are in `desktop/src/test/kotlin/zoukan/desktop/e2e/`, one `*JourneyTest` class per
journey. They drive the real UI against fixtures on 127.0.0.1. A journey never uses a live site.

The kit:

- `Journey.start(name)` starts the app's runtime with a fresh data folder, starts a
  `FixtureServer`, and installs a `FixtureSource` for it. Call `journey.close()` in `@AfterEach`.
- `FixtureServer` serves a fixed catalogue and page images on a free port. It records every
  request (`requests`, `requestLog`), so a test can check what the app asked for.
- `FixtureSource` reads the server as an extension would. It has its own settings, a cookie, HTML
  descriptions and scrambled pages, for the journeys that test those.
- `FixtureStore` serves an extension store on the same server, with real signed extension jars,
  for the install path.
- The page images come from `desktop/src/test/resources/e2e-fixtures/generate.py`. Each page is
  one known colour, one chapter per image format.

Inside a journey:

- `step("...") { }` prints the step's title, so the log shows where a journey stopped.
- `waitFor(matcher)` and `waitOrDump("what") { condition }` wait up to 30 seconds. On a timeout
  they save a screenshot, print the texts on screen, the fixture requests and the app's state.
  Wait for state like this. Do not sleep.
- `readThrough(pages, label)` reads pages in the paged reader and checks that each one drew.
- `pageDrew(page)` checks from a screenshot that a page image on screen is the page's colour.
- `addToLibrary(title)` adds a fixture title with its chapters, for journeys about what comes
  after.
- `shot(title)` saves a screenshot.

Screenshots go to `desktop/build/reports/journeys/`. The folder is emptied when `journeyTest`
starts.

The runtime can start only once per JVM. So each journey is its own class with one test, and
`journeyTest` runs each class in a JVM of its own. A flow that changes state gets its own journey.

## The button crawl

`ButtonCrawlTest` (task `:desktop:buttonCrawlTest`) clicks every button of the app. It starts the
runtime and seeds the library as the smoke test does, and opens the same checks (`SmokeChecks.kt`
holds them for both), plus five states whose buttons need data: a library selection
(`flow:library.selection`), a selection of an online entry for the download actions
(`flow:library.selection-download`), an entry with every chapter selected (`flow:manga.chapter-selection`),
the reader on a middle page for its keys (`flow:reader.keys`) and the reader with its bars shown
(`flow:reader.bars`). Settings search, the theme row and the
Settings screen itself are skipped: their rows are the Settings pages'.

For each check it collects every clickable node of the topmost root (a dialog or menu over the
window is modal, so a user can click only in it), scrolling each list to reach its rows. Of
several buttons with the same key (the rows of one list, the days of a calendar) it clicks the
first two. Then, for each button, it opens the check again, clicks
the button and waits for the app to settle. A click fails the run when:

- it throws, the composition throws, or a thread has an uncaught exception;
- the app logs an ERROR while it runs;
- the window stops answering (a native modal dialog that does not go through `SystemEffects`);
- nothing observable happened: no navigation, no dialog or menu opened or closed, nothing on
  screen changed, no setting or database row changed, no toast or notification, no callback of a
  dialog rendered directly by the test, and no outside effect.

Outside effects go through `SystemEffects` (`desktop/src/main/kotlin/zoukan/desktop/app/SystemEffects.kt`):
opening a web page or a folder, the file and folder pickers, the clipboard, an update's installer
and quitting. The crawl puts a recorder in its place, so a click never leaves the test, and a
picker answers "cancelled". New code that opens something outside the window must use it too.

### Other inputs

The same pass covers what a user does other than click, with the same snapshot, signature,
baseline and restore. Each gets a key of its own, the node's key with a suffix:

| Input | Key | What the crawl does |
| --- | --- | --- |
| Right click, and other semantics custom actions | `<node> [action: <label>]` | Runs the action. `onRightClick` (`ui/common/Modifiers.kt`) gives every right-click site a custom action ("More actions", or "Copy to clipboard" where it copies), which screen-reader and keyboard users reach too. A menu it opens is crawled one level down, as a click's. The one long press that opens a menu (the entry's title, `EntryInfoMenus.kt`) has the same menu as its right-click action; other long presses only show a tooltip, so the crawl does not long-press. |
| Text field | `<node> [type]`, `<node> [type, enter]`, `<node> [type, escape]` | Gives the field the focus and types `Alpha` (the seeded entry, so a search finds it), records; then presses Enter and records again. A second task types and presses Escape. Password fields are left out (`AppLockJourneyTest` and the sync and tracker journeys cover them). |
| Slider | `<node> [slider max]`, `<node> [slider min]` | Sets it to its maximum, records, then to its minimum. Material's `setProgress` also calls `onValueChangeFinished`. |
| Keyboard shortcut | `<check>/key:<keys>` | Presses the key into the window as the window passes it on (the focused node, then the app's shortcuts on the root, then Escape as Back). Every `Shortcuts.actionFor` action, on the library (`tab:Library`, `tab:More` for Ctrl+1) or an entry (Escape, Alt+Left), and every `ReaderKeys.bindings` key on `flow:reader.keys`. |

A text field on a screen fails when neither typing nor Enter did more than show the text (no
setting, search, list or screen took it); a field in a dialog that only shows the text is
`field-local`, since the dialog's own button stores it. A dialog with a disabled button and an
empty field gets `Crawl` typed in before its buttons are collected, so the button it held back is
crawled too. A slider fails when neither end stored anything or changed anything but the slider.
The crawl clears what was typed into the root screen's fields (the library search) between tasks,
and puts the window back from fullscreen (the reader's F).

A button that opens a menu gets its items clicked as well, one level down, and an item that opens
a submenu gets the submenu's items clicked, keyed `<button> > <item> > <submenu item>`: the
library entry's "More actions" > "Download" > "Next chapter". A menu inside a submenu is not
crawled. A click opened a menu when a new popup appeared, or when the open menu's items were all
replaced (a submenu shown in place of its menu, or a popup in place of the one that closed);
`ButtonCrawlMenuTest` tests that rule. Such a click is `menu`, never `ui`, so a submenu's opener
("Download") is not held to the rule for labels that change data: its items are.

The seeded entries are local-source, which has no downloads, so `flow:library.selection-download`
adds an online entry (Aeon, of a test source whose pages never come) for its own clicks and
removes it after each. A download from it stays in the queue, so "Next chapter" and the other
choices are `queue+` in every run; the cleanup clears the queue.

Dialogs that a click opens are not crawled; the named ones have checks of their own. Between clicks, the crawl puts back
the settings a click changed and the seeded library (Alpha, Bravo, their chapters and the Smoke
category). HTTP goes to a closed local port, so the crawl runs offline on every machine.

### Effect signatures

Each click gets an effect signature: the things it did, as sorted tokens that are the same in
every run (digits become `#`). The tokens:

| Token | Meaning |
| --- | --- |
| `nav:<package>.<Screen>`, `nav:back`, `nav:host` | It opened that screen, went back, or opened a dialog the test renders directly. |
| `dialog:"<title>"`, `menu`, `close` | It opened a dialog (named by its title) or a menu (a submenu too), or closed one. |
| `pref:<key>` | It changed that preference. The crawl also checks the value reached the file on disk. |
| `db:<table>.<column>`, `db:<table>+`, `db:<table>-` | It changed that column, or added or removed rows. The report also names the rows: `chapters.read[Alpha/Chapter 1]`. |
| `queue+`, `queue-`, `files:downloads-`, `files:local-` | It changed the download queue, or added or removed files in the downloads or local folders. |
| `job:library-update` | It started a library update. |
| `tab:<Tab>`, `selection` | It switched the home tab, or changed a selection's "N selected" count. |
| `focus:<node>` | A key moved the focus to that node (keyboard shortcuts only: a click or typing moves the focus as a side effect). |
| `reader:<page\|menu\|zoom\|fullscreen\|help\|auto-scroll\|settings\|chapters>` | It changed that of the open reader. The reader exposes these through `ReaderKeys.StateKey`, a semantics property for tests that screen readers do not see. |
| `browse:<host>`, `folder:<path>`, `pick:open[<types>]`, `pick:save[<types>]`, `pick:folder`, `clipboard`, `trash:<name>`, `install`, `exit` | An outside effect, through `SystemEffects`. A link must be an http(s) address, and a folder must exist inside the data or storage folder. |
| `toast:"<text>"`, `notification:"<title>"` | It showed a toast or a notification. |
| `calls-back` | It called a callback of a dialog that the test renders directly. |
| `ui` | Only the screen changed. |
| `own-value` | Only the control's own value changed: a text field's text or focus, a slider's position. |
| `field-local` | A text field in a dialog only showed what was typed; the dialog's button stores it. |
| `field`, `key-unused`, `at-end-already` | Nothing changed: the field refused the text, the field or screen did not use Enter or Escape, or a slider (or N and P in the reader) was already at that end. |
| `selected-already`, `field` | Nothing changed, rightly: the page, tab or option was already chosen, or the text field already had the focus. |
| `logs:offline` | The app logged an error only because the crawl runs offline or without Chromium. Any other error fails the click. |

A switch, checkbox or choice whose click changed a preference or the database gets a round trip:
the crawl opens the check again without putting the state back, and the button must show the
stored state (a switch stays on).

A click fails the run when, besides the errors above, its signature is empty; when it is only
`ui` for a Settings switch or choice, or for a button whose label says it changes data ("Mark
as read", "Delete", "Download"); when a contract does not hold; or when its signature differs
from the inventory. The contracts are in `Contracts` (`ButtonEffects.kt`): for example, "Add to
library" on Alpha's screen must write `mangas.favorite[Alpha]`, and a switch of the library's
filter, sort and display options must write a preference.

### The checked-in files

Two files in `desktop/src/test/resources/` go with it:

- `button-inventory.txt`: every button and its signature, `<button key>  <signature>` (two spaces
  between). A button key is `<check key>/<node id>`, and `<check key>/<button> > <item>` for a menu
  item. The crawl fails when a button appears, goes or does something else. Some signatures
  depend on the platform (paths, dates) and on which buttons ran before in the same JVM, so the
  inventory is recorded on Windows, in one unsharded run, the same way `longChecks` runs
  the crawl. After a change on purpose, regenerate it there:
  `.\gradlew.bat :desktop:buttonCrawlTest -Pzoukan.crawl.updateBaseline=true`, and review the
  diff: each changed line is a change in what a button does. A filtered or sharded run rewrites
  only the buttons it crawled, and a sharded run can give other signatures, so do not record the
  inventory with one. Each run also writes what it found, in the same format, to
  `desktop/build/reports/coverage/button-inventory-found.txt`. The crawl's data folder is
  `<temp>/zoukan-crawl<18 digits>`, always the same number of digits, so two runs on Windows give the
  same keys.
- `button-crawl-allowlist.txt`: buttons allowed to do nothing, or only change the screen,
  `<button key>  # <reason>`. Such a button is usually a bug, so fix it rather than list it. A line
  whose button now does something more fails a full run.

Each run writes every button with what clicking it did to `desktop/build/reports/coverage/buttons.md`.
`-Pzoukan.crawl.only=<regex>` crawls only the checks whose name or key matches, to debug one, and
`-Pzoukan.shard=i/n` crawls every n-th check key, to split a debug run.

### What the crawl leaves to other tests

Some inputs are slow or flaky to crawl, or leave the window, so dedicated tests cover their logic:

- Library Shift-click range and Ctrl/Cmd-click toggle: `LibraryClickSelectionTest`.
- The paged reader's wheel page turn and Ctrl/Cmd + wheel zoom: `PagedWheelTest`.
- The mouse back button: `ShortcutsBackGuardTest`.
- Closing the window to the tray, and the one-time notice: `AppTrayTest` (`onWindowClose`).
- The tray menu's update and pause or start downloads: `AppTrayTest` (`trayMenu`). The real OS tray and its notifications are not tested.
- The Resume button's docking side: `ResumeButtonDockTest`, and the stored side `ReadButtonPositionTest`.
- The reader's key table: `ReaderKeysTest`.
- Drag to reorder: `CategoriesJourneyTest` drags the Edit categories handle. Swipe: `ChapterListTest` swipes a chapter row.
- Not tested: a second instance handing over to the first (two processes), and windows across several monitors.

## Usability checks

Every smoke check also looks at what is on screen for four kinds of usability problem, in the
light and the dark theme at 1280x820 and at 800x560 (`ScreenSmokeSmallWindowTest`):

- `UNLABELLED_CLICKABLE`: a clickable node with no text, content description or state description,
  so a screen reader cannot name it.
- `TEXT_OVERFLOW`: text in a button, tab, checkbox, switch or dialog title that is clipped or cut off.
- `ESCAPE_NOT_DISMISSING`: a dialog whose dismiss is not called by Escape (or is, when it blocks
  dismissal on purpose).
- `TAB_TRAVERSAL`: Tab that gets stuck, or does not reach every control, in a dialog or settings page.
  It never fails a run: a multi-line text field keeps Tab as input, and the check stops after 30 presses.

A new finding of the first three rules (not allowlisted) fails the smoke test, in every variant;
`TAB_TRAVERSAL` findings are only reported. `./gradlew :desktop:wiringTest` writes one file per window size
in `desktop/build/reports/usability/` and `usabilityReport` merges them into
`desktop/build/reports/usability.md`: a summary per rule and variant (new and allowlisted), the new
findings, the allowlisted ones with their reasons, and allowlist entries that matched nothing (read
that list after a full run only).

A finding names the check's coverage-matrix key, the rule and a node: a test tag, the label of the
nearest labelled node (`in "Alpha"`), or its position (`@474,232`). To allowlist a finding that is not a
usability problem, add a line to `desktop/src/test/resources/usability-allowlist.txt`:

    <key> | <RULE> | <node id> | <reason>

`<key>` and `<node id>` may end with `*` to match a prefix. A reason is required and says why this is not
a problem. An entry with no reason, an unknown rule, or a key with no coverage-matrix row fails the
smoke test, with the line number. Do not allowlist a real problem to clean the report: fix it.

The failure message names the variant, the key, the rule and the node. Escape on a dialog that blocks
dismissal on purpose belongs in `ESCAPE_BLOCKED_ON_PURPOSE` (`Usability.kt`), not in the allowlist.

## Screenshot comparison

About twenty curated screens and dialogs (`ScreenshotKeys.curated`, named by their coverage-matrix
key) are compared with golden images in four variants: light and dark, at 1280x820
(`ScreenSmokeTest`) and 800x560 (`ScreenSmokeSmallWindowTest`). The smoke test takes them after its
usability rules. The check is off by default and costs nothing then.

```bash
./gradlew :desktop:wiringTest -Pzoukan.screenshots=verify   # compare with the goldens (Linux only)
./gradlew :desktop:wiringTest -Pzoukan.screenshots=record   # write the goldens
```

- Goldens: `desktop/src/test/screenshots/linux/<size>-<theme>/<key>.png`, with `:` and `/` in the
  key replaced by `_`. They are recorded on ubuntu-24.04 under xvfb with fonts-dejavu-core and
  fonts-noto-cjk, by the `Screenshots` workflow (`.github/workflows/screenshots-record.yml`).
  Fonts and the rasteriser differ on Windows and macOS, so `verify` there only saves the captures
  and compares nothing; goldens recorded there are gitignored.
- A pixel is changed when a channel differs by more than 8; a screen fails when more than 0.1% of
  its pixels changed. Parts that change from run to run (dates, "ago" text, async covers, a caret,
  the temporary data folder) are masked per key in `ScreenshotKeys`.
- A failed `verify` writes `expected.png`, `actual.png` and `diff.png` (changed pixels red over the
  dimmed actual) per screen to `desktop/build/reports/screenshots/<variant>/<key>/`, and a summary
  to `desktop/build/reports/screenshots/report.md`. A curated key that the run never reached also
  fails a full `verify`, so a golden cannot silently stop being compared.
- `captureAllRoots()` (`Screenshots.kt`) draws every root (dialogs, menus, toasts) over the window.
  Journeys and the `EndToEnd*Test` classes use it for their screenshots too.

After an intended visual change, record new goldens: run the `Screenshots` workflow with mode
`record` on your branch (`gh workflow run screenshots-record.yml --ref <branch>`), download its
`screenshot-goldens` artifact into `desktop/src/test/screenshots/`, look at the changed images, and
commit them. To add a screen, add its key to `ScreenshotKeys.curated` (and masks if it shows
anything that changes per run) and record.

## Gated live tests

The `EndToEnd*Test` classes in `desktop/src/test/kotlin/zoukan/desktop/ui/` skip themselves
unless you pass `-Pzoukan.e2e=true`. Most of them install a real extension and use its site, so
they need the network; the offline flows are journeys (`./gradlew :desktop:journeyTest`). They use a throwaway data folder and save
screenshots to `desktop/build/reports/e2e/`.

```bash
./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndReadTest"
./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndDownloadTest"
./gradlew :desktop:test -Pzoukan.e2e=true -Pzoukan.e2e.package=eu.kanade.tachiyomi.extension.en.weebcentral --tests "*EndToEndReadTest"
```

Always pass `--tests`. With `-Pzoukan.e2e=true`, `test` no longer leaves out the wiring tests and
the journeys, and runs every test class in a JVM of its own.

Some other tests use the network and skip themselves unless the environment variable
`ZOUKAN_LIVE=true` is set (some also accept `-Dzoukan.live=true` on the test JVM). Run them one at a
time, with `--tests`:

- `DohLiveTest`: resolves real names through every DNS over HTTPS provider.
- `ChromiumDohLiveTest`, `WebViewFrameCallbacksLiveTest`: drive an installed Chromium
  (`ZOUKAN_CEF_DIR=<data>/browser`).
- `UpdateLiveTest`: runs the update check against the real GitHub releases as version 1.0.6
  (`ZOUKAN_LIVE_UPDATE_FROM` changes it), downloads this platform's installer (about 300 MB) and its
  `.sig`, and checks that the release signature verifies under the committed
  `zoukan-release-key.pem` and that a copy with one byte flipped, or a changed signature, is
  refused. When the newest release has no `.sig` (releases from before the release signature), it
  checks that the installer is refused and then skips the signature checks. 1.1.0 is the first
  release with `.sig` files, so the positive path can pass only once 1.1.0 is published. It uses an in-memory
  secret store and a temporary folder, so it touches no app data and no OS secret store.

```bash
ZOUKAN_LIVE=true ./gradlew :desktop:test --tests "*UpdateLiveTest" --rerun
```

Two tests in `StorageFolderPrivacyTest` change the real Spotlight Search Privacy list of the
macOS account that runs them, and one of them makes its folders under `zoukan-spotlight-test` in
the test home folder (`TestHome.root()`, under `desktop/build/test-home/<task>`), not the real home.
They skip themselves unless the environment variable `ZOUKAN_TEST_REAL_SPOTLIGHT=true` is
set (or `-Dzoukan.test.realSpotlight=true` on the test JVM). They take their entries off the list
and remove their folders when they end, and first clear what an interrupted earlier run left. The
other tests of the class use stand-ins and never touch the list.

```bash
ZOUKAN_TEST_REAL_SPOTLIGHT=true ./gradlew :desktop:test --tests "zoukan.desktop.storage.StorageFolderPrivacyTest" --rerun
```

`extensionWiringTest` downloads every extension of two stores, so it runs only in `nightlyCheck`
and `longChecks` (on Windows before each merge to master). The stores are `SampleStores.ALL`, defined in the tests only (the app names no store): Keiyoushi (about 1,700
extensions) and yuzono's "Cursed" store (six NSFW extensions with their own signing key). Only the
tests use the yuzono store; the app does not add or trust it. Each store runs in a
test task and JVM of its own (`extensionWiringTestKeiyoushi`, `extensionWiringTestYuzono`), one
after the other, and writes its own report:
`desktop/build/reports/e2e/extensions-wiring-<store>.md`. A store that lists far fewer extensions
than expected (under 1,000 for Keiyoushi, none for yuzono) fails its task: the store moved or its
index is a stub.

- `-Pzoukan.e2e.stores=yuzono` picks the stores (comma-separated; default both).
- `-Pzoukan.e2e.package=en.mangapill,all.mangadex` limits it to some extensions. Each store checks
  the ones it lists.

```bash
./gradlew :desktop:extensionWiringTest -Pzoukan.e2e.stores=yuzono
./gradlew :desktop:extensionWiringTest -Pzoukan.e2e.stores=keiyoushi -Pzoukan.e2e.package=en.mangapill,all.webtoons
```

The live harness (`./gradlew :desktop:run --args="harness --store <url>"`) is part of the app, so
it names no store: `--store` takes a store's index URL and is required (repeatable). It tests
`CompatibilityHarness.DEFAULT_PACKAGES` unless `--packages` or `--all` says otherwise, and has no
minimum-size check (`SampleStores` in the tests holds the stores' URLs and minimum sizes).
Each store writes its own report, `extensions-<store>-<platform>.md` in the harness profile, where
`<store>` is the URL's GitHub owner or host (`owner-2` and so on for a second store of the same owner). Over
several stores, a `--packages` entry is tested in the stores that list it, and fails the run only
when no store lists it.

`--doh <name|off>` sets DNS over HTTPS for the run, for OkHttp and the hidden Chromium alike:
`cloudflare`, `google`, `adguard`, `quad9`, `alidns`, `dnspod`, `360`, `mullvad`, `controld`,
`njalla`, or `off` for the system's DNS. It sets the DNS preference of the harness profile, so later
runs without `--doh` keep it; the app's own profile is never touched. Without it the run uses the
harness profile's setting. The run prints the server it uses. Use it when the local DNS blocks or
redirects a site, or to check a provider:

```bash
./gradlew :desktop:run --args="harness --doh quad9 --store https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json --packages en.comix"
```

The "First image" column gives the first page's type, size and size in pixels (`ok image/webp
1 KB 720x40`, say), so a placeholder, a tiny tile or a scrambled image shows apart from a real page.
