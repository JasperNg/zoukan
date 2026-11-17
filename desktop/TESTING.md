# Testing the desktop app

This file explains the desktop test tiers, how to run them, and how to keep the coverage matrix
up to date. Run every command from the repository root. On Windows, use `gradlew.bat`.

## Tiers

| Tier | Gradle task | What it catches | When it runs |
| --- | --- | --- | --- |
| Unit | `:desktop:test` | Wrong logic: mapping, state, parsing, single composables. Also checks the coverage matrix (`CoverageMatrixTest`). | Every build and every pull request. |
| Wiring | `:desktop:wiringTest` | Code that compiles but crashes when it runs: a missing Injekt registration (`InjektRegistrationsTest`), a screen or dialog that throws when it opens (`ScreenSmokeTest`). | Every build: `check`, and so `build`, depends on it. CI runs it under `xvfb-run -a` on Linux. |
| Button crawl | `:desktop:buttonCrawlTest` | A button that throws, logs an error, hangs the window or does nothing, and a button that appeared or went without the inventory being updated. Clicks every button of every check of the smoke test. | Every pull request and every push to master: CI runs it in 4 shards under `xvfb-run -a`. Not in `check`: it takes about half an hour in one JVM. |
| Journeys | `:desktop:journeyTest` | Broken user flows: browse, read, download, back up, change source settings. Runs offline against a local fixture server. | Every pull request and every push to master: CI runs them in 3 shards under `xvfb-run -a`. Also in the nightly check. |
| Nightly | `:desktop:nightlyCheck` | The journeys, plus `extensionWiringTest`, which downloads every extension of the Keiyoushi store and of yuzono's "Cursed" store and checks it loads on the desktop. | By hand, or by `desktop/packaging/check/nightly-windows.ps1`. |
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
./gradlew :desktop:wiringTest --tests "*ScreenSmokeSmallWindowTest" -Pzoukan.smoke.only="settings:"   # at 800x560
./gradlew :desktop:journeyTest                       # every offline journey
./gradlew :desktop:buttonCrawlTest                   # click every button (long: CI runs it in shards)
./gradlew :desktop:buttonCrawlTest -Pzoukan.crawl.only="settings:Library"
./gradlew :desktop:buttonCrawlTest -Pzoukan.shard=2/4                     # the second of four shards
./gradlew :desktop:buttonCrawlTest -Pzoukan.crawl.updateBaseline=true     # rewrite button-inventory.txt
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

## The button crawl

`ButtonCrawlTest` (task `:desktop:buttonCrawlTest`) clicks every button of the app. It starts the
runtime and seeds the library as the smoke test does, and opens the same checks (`SmokeChecks.kt`
holds them for both), plus four states whose buttons need data: a library selection
(`flow:library.selection`), an entry with every chapter selected (`flow:manga.chapter-selection`),
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

A button that opens a menu gets its items clicked as well, one level down. Dialogs that a click
opens are not crawled; the named ones have checks of their own. Between clicks, the crawl puts back
the settings a click changed and the seeded library (Alpha, Bravo, their chapters and the Smoke
category). HTTP goes to a closed local port, so the crawl runs offline on every machine.

### Effect signatures

Each click gets an effect signature: the things it did, as sorted tokens that are the same in
every run (digits become `#`). The tokens:

| Token | Meaning |
| --- | --- |
| `nav:<package>.<Screen>`, `nav:back`, `nav:host` | It opened that screen, went back, or opened a dialog the test renders directly. |
| `dialog:"<title>"`, `menu`, `close` | It opened a dialog (named by its title) or a menu, or closed one. |
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
  item. The crawl fails when a button appears, goes or does something else. After a change on
  purpose, regenerate it with `-Pzoukan.crawl.updateBaseline=true` (a filtered or sharded run
  rewrites only the buttons it crawled) and review the diff: each changed line is a change in
  what a button does. Each run also writes what it found, in the same format, to
  `desktop/build/reports/coverage/button-inventory-found.txt`, and CI uploads it with each shard
  (artifact `button-crawl-<shard>`), so a new inventory can be put together from CI without a
  local crawl: keep the header and append the sorted lines of the four shards' files.
- `button-crawl-allowlist.txt`: buttons allowed to do nothing, or only change the screen,
  `<button key>  # <reason>`. Such a button is usually a bug, so fix it rather than list it. A line
  whose button now does something more fails a full run.

Each run writes every button with what clicking it did to `desktop/build/reports/coverage/buttons.md`.
`-Pzoukan.crawl.only=<regex>` crawls only the checks whose name or key matches, to debug one, and
`-Pzoukan.shard=i/n` crawls every n-th check key, as CI does.

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
  checks that the installer is refused and then skips the signature checks. It uses an in-memory
  secret store and a temporary folder, so it touches no app data and no OS secret store.

```bash
ZOUKAN_LIVE=true ./gradlew :desktop:test --tests "*UpdateLiveTest" --rerun
```

`extensionWiringTest` downloads every extension of two stores, so it is part of the nightly
check only. The stores are `CompatibilityHarness.SAMPLE_STORES`: Keiyoushi (about 1,700
extensions) and yuzono's "Cursed" store (six NSFW extensions with their own signing key). Only the
tests and the harness use the yuzono store; the app does not add or trust it. Each store runs in a
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

The live harness (`./gradlew :desktop:run --args="harness"`) also runs over both stores by
default, each with its own sample: Keiyoushi's 30 (`CompatibilityHarness.KEIYOUSHI`) and all six
of yuzono's. `--store keiyoushi` or `--store yuzono` (repeatable, or a store URL) picks the stores.
Each store writes its own report, `extensions-<store>-<platform>.md` in the harness profile. Over
several stores, a `--packages` entry is tested in the stores that list it, and fails the run only
when no store lists it.
