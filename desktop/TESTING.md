# Testing the desktop app

This file explains the desktop test tiers and how to run them. Run every command from the
repository root. On Windows, use `gradlew.bat`.

## Tiers

| Tier | Gradle task | What it catches | When it runs |
| --- | --- | --- | --- |
| Unit | `:desktop:test` | Wrong logic: mapping, state, parsing, single composables, Android API linkage (`AppLinkageTest`), contract and source-scan rules. | Every build, and on GitHub (`.github/workflows/ci.yml`): the whole tier (with `:extension-jvm:test`, `:domain:desktopTest`, `:android-compat:test`, `:backup:desktopTest`, `:data:desktopTest`, `:core:common:desktopTest` and `:source-api:desktopTest`, the shared modules' own tests) on Linux, Windows and macOS on every pull request and every push to master. |
| Wiring | `:desktop:wiringTest` | Code that compiles but crashes when it runs: a missing Injekt registration (`InjektRegistrationsTest`), runtime start (`ZoukanRuntimeStartTest`), a screen or dialog that throws when it opens (`ScreenSmokeTest`). | Every build: `check`, and so `build`, depends on it. CI runs it under `xvfb-run -a` on Linux. |
| Journeys | `:desktop:journeyTest` | Broken user flows: browse, read, download, back up, change source settings. Runs offline against a local fixture server. | On Windows on every pull request and every push to master that changes code (the `journeys` job in `.github/workflows/ci.yml`, see [On GitHub](#on-github)), and on demand. |
| Extension wiring | `:desktop:extensionWiringTest` | Downloads every extension of the sample stores and checks it loads on the desktop. | Every night on Windows and macOS in `.github/workflows/nightly.yml` (see [On GitHub](#on-github)), and on demand. |
| Android compile and unit tests | `:app:compileDebugKotlin` and `compileDebugKotlinAndroid` of `:data`, `:domain`, `:core:common`, `:source-api`, then `:app:testDebugUnitTest` and `:domain:testDebugUnitTest` | A desktop change to shared Android code (`app/.../data/track/`, `data/`, `domain/`) that breaks the Android build or the Android app's unit tests (they run on the JVM, not on a device). | Every pull request and every push to master: the "Android compile and unit tests" job in `.github/workflows/ci.yml`, described in the comment above it. Locally it needs `ANDROID_HOME` set to an Android SDK. |

Use the cheapest tier that can catch the bug:

- Put logic in a unit test. Most tests belong here. They are fast and give clear failures. They
  live in `desktop/src/test/kotlin/zoukan/desktop/`, in the same package as the code.
- Rely on the wiring tier to show that a screen or dialog opens. You rarely write a new test for
  it: the smoke test finds new screens and pages by itself (see [The smoke test](#the-smoke-test)).
- Write a journey only for a flow that crosses screens and needs the real UI, such as "add to
  library, then read". Do not test logic through a journey that a unit test could test.

`DownloadManager.kt` and `KcefWebViewProvider.kt` are too large to reason about a change by
reading alone. Before you change either file, first write tests that pin the current behaviour of
the part you will change, and see them pass on the unchanged code. Then make the change.

## The release gate

The release gate on GitHub (`ci.yml`, which `desktop-build.yml` runs before it publishes) runs the
offline checks: Spotless, the build and unit tests, the wiring tests, the unit tests on Windows and
macOS, the Android compile and unit tests, and the offline user journeys on Windows. Its jobs run
side by side; the Windows and macOS runners are the slowest, so they set how long the gate takes.

Every pull request and every push to master (and so every release, since `desktop-build.yml`
calls `ci.yml`) runs the whole unit tier on Windows and macOS as well as on Linux: `:desktop:test`,
`:extension-jvm:test`, `:domain:desktopTest`, `:android-compat:test`, `:backup:desktopTest`,
`:data:desktopTest`, `:core:common:desktopTest` and `:source-api:desktopTest`.
A test that fails only on Windows or macOS (file names, links and junctions, the secret stores,
the macOS marks) therefore fails the pull request, not the first master run after the merge. The
"PR gate" job needs all of these jobs. Tests for one system skip themselves on the others with
`assumeTrue`.

### On GitHub

- `.github/workflows/ci.yml` runs `:desktop:journeyTest` in its `journeys` job on a Windows
  runner for every pull request and push to master that changes code (docs-only changes skip it),
  and gates `pr-gate`.
- `.github/workflows/nightly.yml` runs every night at 03:17 UTC on master, in the main repository
  only (forks skip it), and opens, updates or closes the `nightly-failure` issue. A run started by
  hand reports to the issue only when its `notify` box is ticked. It does not gate a merge. It
  runs `:desktop:extensionWiringTest` on Windows and macOS.

## Commands

```bash
./gradlew spotlessCheck :desktop:build --continue    # before every pull request: unit and wiring tiers
./gradlew :desktop:test                              # unit tests
./gradlew :desktop:test --tests "*AppLinkageTest"    # one class, or a pattern
./gradlew :desktop:wiringTest                        # Injekt registrations, runtime start and the smoke test
./gradlew :desktop:wiringTest --tests "*ScreenSmokeTest" -Pzoukan.smoke.only="tab:Library"
./gradlew :desktop:journeyTest                       # every offline journey
./gradlew :desktop:journeyTest --tests "*BrowseAndReadJourneyTest"
./gradlew :desktop:extensionWiringTest               # every extension of each sample store
```

Notes:

- `:desktop:test` leaves out the wiring tests and the journeys. They start the app's runtime,
  which can start only once per JVM, so their own tasks run them.
- The wiring tests and the journeys open real windows, so they need a display. On Linux
  without one, run Gradle under `xvfb-run -a`, as CI does for the wiring tests.
- `-Pzoukan.smoke.only` is a regular expression. It runs only the smoke checks whose name or
  key matches it, for example `"tab:Library"`, `"browse:"` or `"dialog new category"`.
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
  are left out, and so are the other Test tasks (`wiringTest`, `journeyTest`,
  the `extensionWiringTest` tasks): they are never instrumented, and a report does not run them.
- The tests are instrumented only when the command line asks for a Kover task, or with
  `-Pzoukan.coverage=true`, so an ordinary `test` run is not slowed down. Switching between the
  two makes the tests run again, since the instrumentation is one of their inputs.
- Use the `unit` reports (`koverXmlReportUnit`). Kover's default reports (`koverXmlReport`) also
  take the Android variants of the multiplatform modules, which compiles them and needs
  `ANDROID_HOME`.

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
`keytool`, `jarsigner` and `jcmd`.

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
planned slowness (the fixture server), and a live test by hand against real Chromium.
`ThreadSleepScanTest` (in `zoukan.desktop.scan`) fails on `Thread.sleep`, `TimeUnit.*.sleep` and
`SystemClock.sleep` in any test source set outside its short allow-list, in which every entry has a
reason.

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
  Versions opened through the start-up path: 4, 14, 20, 21, 26, 27, 28, 38 and the one before
  the newest, and 13 and 14 with a row whose parent is gone.
  Every version from 1 is built and seeded. `MigrationSeed` holds the known rows and what each
  migration does to them. A new migration that adds a table or a column fails these tests until
  `MigrationSeed` has rows for the table, or says what old rows get in the column.
  Only the newest version occurs in the field: the desktop's first release already had it, and data
  from Android arrives in backups, not database files. The older versions are tested for
  completeness, since the migrations are shared with the Android app. Pinned as they are: a
  database from before 4 fails at `13.sqm` (`3.sqm` names a column `COL_MANGA_SOURCE`), and two
  favourites with the same gallery fail `26.sqm`. Each failure leaves the database and the copy
  made before the migration as they were. The start-up path migrates with foreign keys off, as
  Android's driver does, so a row whose parent is gone passes `13.sqm` and `14.sqm`
  (`MigrationForeignKeysTest` checks a table rebuild keeps its children), and it rewrites
  `20.sqm`'s trigger, whose double-quoted string this SQLite rejects, before migrating and right
  after `20.sqm`, so `26.sqm`'s table rename (which checks every trigger) passes. A database that went through
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
for any input, in bounded time. `zoukan.desktop.extension.ExtensionFilePropertyTest` does the same
for extension files: a damaged signed jar or APK gives one signer or an `ExtensionException` from
every step that reads it (signature, manifest, conversion, install), and a refused file leaves
nothing in the extensions folder. The Android name code they compare with is copied in
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

- every Voyager screen of the app, found by reflection over compiled classes (`UiInventory`);
- every home tab, and every Browse page, opened by its tab's test tag `browse-page-<BrowsePage>`, which must then be selected;
- every Settings page, and Settings search;
- the dialogs that other code can call, rendered directly (see `dialogs()`);
- some dialogs private to a screen, opened through the UI (see `uiDialogs()`), and the app lock
  and first-run setup.

On each screen it scrolls every list to its end, so every row composes. A check fails if anything
throws: in composition, in an effect, in a screen model, or on any other thread. The run also
fails if the log holds Injekt's "No registered instance or factory".

Each check has a `key` (`screen:...`, `tab:...`, `browse:...`, `settings:...`, `dialog:...`,
`state:...`). Variants of one screen share a key. Before any check runs (and before the
`-Pzoukan.smoke.only` filter), the test verifies that every discovered screen, tab, Browse page and
Settings page has a check (`HomeScreen` is the exception, as every check renders it). If not, it
fails and names the missing keys:

```
java.lang.IllegalStateException: screens, tabs or pages that no smoke check opens:
  tab:History
```

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
2. The smoke test opens the page without a change: it goes through every `SettingsCategory`
   entry. A Settings page needs no test tag. Run its check:
   `./gradlew :desktop:wiringTest --tests "*ScreenSmokeTest" -Pzoukan.smoke.only="settings:<Entry>"`.
3. Put each dialog of the page in a `...Dialog` function, and add its smoke check (see below).

A page that shows its rows only in some state needs its own check. `settings()` has an example:
the MangaDex page installs an offline MangaDex stand-in while its check runs.

### Adding a dialog

Add a check to `dialogs()` with the dialog's key:

```kotlin
dialog("dialog:manga.CoverDialog", "CoverDialog") { CoverDialog(f.alpha, noop) },
```

For a dialog that only opens through the UI, add a `SmokeCheck` to `uiDialogs()` with a `drive`
block that clicks to it. Undo any state you change in `cleanup`, or later checks will see it.

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

## Gated live tests

Some tests use the network and skip themselves unless the environment variable
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

`extensionWiringTest` downloads every extension of two sample stores, so it runs in `nightly.yml`
and on demand.
The stores are `SampleStores.ALL`, defined in the tests only (the app names no store): Keiyoushi (about 1,700
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

The live harness (`./gradlew :desktop:runTools --args="harness --store <url>"`) lives in `tools`
rather than the shipped app, and names no store: `--store` takes a store's index URL and is required (repeatable). It tests
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
./gradlew :desktop:runTools --args="harness --doh quad9 --store https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json --packages en.comix"
```

The "First image" column gives the first page's type, size and size in pixels (`ok image/webp
1 KB 720x40`, say), so a placeholder, a tiny tile or a scrambled image shows apart from a real page.
