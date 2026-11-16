# Testing the desktop app

This file explains the desktop test tiers, how to run them, and how to keep the coverage matrix
up to date. Run every command from the repository root. On Windows, use `gradlew.bat`.

## Tiers

| Tier | Gradle task | What it catches | When it runs |
| --- | --- | --- | --- |
| Unit | `:desktop:test` | Wrong logic: mapping, state, parsing, single composables. Also checks the coverage matrix (`CoverageMatrixTest`). | Every build and every pull request. |
| Wiring | `:desktop:wiringTest` | Code that compiles but crashes when it runs: a missing Injekt registration (`InjektRegistrationsTest`), a screen or dialog that throws when it opens (`ScreenSmokeTest`). | Every build: `check`, and so `build`, depends on it. CI runs it under `xvfb-run -a` on Linux. |
| Journeys | `:desktop:journeyTest` | Broken user flows: browse, read, download, back up, change source settings. Runs offline against a local fixture server. | On request, and in the nightly check. |
| Nightly | `:desktop:nightlyCheck` | The journeys, plus `extensionWiringTest`, which downloads every Keiyoushi extension and checks it loads on the desktop. | By hand, or by `desktop/packaging/check/nightly-windows.ps1`. |
| Live | `:desktop:test -Pzoukan.e2e=true` | The `EndToEnd*Test` classes. Most of them use real sites and need the network. | Only when you ask for them. Never in CI. |

Use the cheapest tier that can catch the bug:

- Put logic in a unit test. Most tests belong here. They are fast and give clear failures. They
  live in `desktop/src/test/kotlin/zoukan/desktop/`, in the same package as the code.
- Rely on the wiring tier to show that a screen or dialog opens. You rarely write a new test for
  it: the smoke test finds new screens and pages by itself (see [The smoke test](#the-smoke-test)).
- Write a journey only for a flow that crosses screens and needs the real UI, such as "add to
  library, then read". Do not test logic through a journey that a unit test could test.
- Use a live test only when the behaviour depends on a real site. Live tests do not count as
  coverage, because CI never runs them.

## Commands

```bash
./gradlew spotlessCheck :desktop:build --continue    # before every pull request: unit and wiring tiers
./gradlew :desktop:test                              # unit tests
./gradlew :desktop:test --tests "*CoverageMatrix*"   # one class, or a pattern
./gradlew :desktop:wiringTest                        # Injekt registrations and the smoke test
./gradlew :desktop:wiringTest --tests "*ScreenSmokeTest" -Pzoukan.smoke.only="tab:Library"
./gradlew :desktop:journeyTest                       # every offline journey
./gradlew :desktop:journeyTest --tests "*BrowseAndReadJourneyTest"
./gradlew :desktop:nightlyCheck --continue           # nightly only: journeys and every extension
```

Notes:

- `:desktop:test` leaves out the wiring tests and the journeys. They start the app's runtime,
  which can start only once per JVM, so their own tasks run them.
- The wiring tests and the journeys open real windows, so they need a display. On Linux without
  one, run Gradle under `xvfb-run -a`, as CI does.
- `-Pzoukan.smoke.only` is a regular expression. It runs only the smoke checks whose name or
  coverage-matrix key matches it, for example `"tab:Library"`, `"browse:"` or `"dialog new category"`.
  Without `--tests "*ScreenSmokeTest"`, `wiringTest` also runs `InjektRegistrationsTest`.
- Gradle can take `:desktop:test` from its build cache. Add `--rerun` to run the tests again
  anyway.
- `journeyTest` and `extensionWiringTest` never count as up to date. They always run.
- Never run two `journeyTest` builds at the same time in one checkout. They share the build
  folder and the screenshot folder.
- Each journey runs in a JVM of its own.

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

## Usability checks

Not added yet. When they land, they are report-only and write `desktop/build/reports/usability.md`.

## Screenshot comparison

Not added yet. This section will explain the golden images and how to update them.

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

`extensionWiringTest` downloads every extension of the Keiyoushi store, so it is part of the
nightly check only. `-Pzoukan.e2e.package=en.mangapill,all.mangadex` limits it to some
extensions. Its report goes to `desktop/build/reports/e2e/extensions-wiring.md`.
