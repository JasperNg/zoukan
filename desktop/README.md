# Zoukan desktop

The desktop app for Windows x64 and Apple Silicon Macs. So far (Phase 6 of the desktop port) it has
the library, browse (sources, extensions, stores, global search, saved searches, the feed), manga
details, updates, history, statistics, settings and per-source settings screens, and the reader,
plus backups, downloads, the local source, scheduled library updates, automatic backups, a tray
icon with notifications, trackers, E-Hentai and ExHentai, merged entries and migration.
[PARITY.md](PARITY.md) compares it with the Android app. It starts the Android compatibility layer (vendored from Suwayomi in
`android-compat/`), installs and loads extensions (`extension-jvm/`), and runs the extension
compatibility harness.

Building needs JDK 21. The Android SDK is not needed: without one, shared code compiles against
the desktop's own Android layer (see `android-stubs/build.gradle.kts`).

## Commands

```bash
./gradlew :desktop:run                                     # open the app
./gradlew :desktop:run --args="status"                     # open the encrypted database, list installed extensions
./gradlew :desktop:run --args="harness"                    # test the 20 default extensions against their sites
./gradlew :desktop:run --args="harness --all --skip-nsfw"  # test every extension in the store
./gradlew :desktop:run --args="browser https://example.org"
./gradlew :desktop:run --args="backup restore <file.tachibk>"     # restore a Komikku, Mihon or SY backup
./gradlew :desktop:run --args="backup create [file] [--private]" # back up (default: <data>/backups)
./gradlew :desktop:run --args="backup check <file.tachibk>"       # round trip in a throwaway profile
./gradlew :desktop:run --args="library update --download 1 --report update.md"  # update the library, download each entry's newest chapter
./gradlew :desktop:run --args="selftest --ui"             # check this machine, in a throwaway profile
./gradlew :desktop:run --args="help"
./gradlew :desktop:packageInstaller                        # MSI on Windows, DMG on macOS (see RELEASING.md)
```

On Windows use `.\gradlew.bat` with the same arguments.

## Installers, updates and crash logs

[RELEASING.md](RELEASING.md) has the installers (an MSI for Windows x64 and a DMG for Apple
Silicon, each with its own Java runtime), code signing and notarization, the clean-install checks,
publishing a release, the update check (Settings > About) and where the log and crash logs go
(`<data>/logs`, `<data>/crashes`).

## Reader

Reading modes are the Android ones (saved per manga, with a default in Settings): paged
left to right, paged right to left, vertical, webtoon and continuous vertical.

| Keys / mouse | Action |
|---|---|
| Left / Right, J / K, Space, Page Up / Page Down | Previous / next page (follows the reading direction) |
| Home / End | First / last page |
| N or `]`, P or `[` | Next / previous chapter |
| `+` / `-` / `0`, Ctrl or Cmd + wheel, double-click | Zoom in / out / reset |
| Wheel, drag | Pan; at the page edge the wheel turns the page (paged modes) |
| Click left or right third | Previous / next page |
| Click the middle, M, Enter | Show or hide the menu |
| F, F11 | Full screen |
| `/` | Shortcut list |
| Escape, Alt + Left, mouse back button | Back |

## Keyboard shortcuts

| Keys | Action |
|---|---|
| Ctrl/Cmd + 1 to 5 | Library, Updates, History, Browse, More |
| Ctrl/Cmd + `,` | Settings |
| Ctrl/Cmd + Shift + U | Update the library |
| Escape, Alt + Left, mouse back button | Back |

The reader's keys win while it is open. Settings > Keyboard shortcuts lists them too.

## Trackers, E-Hentai, merged entries

- **Trackers** use the Android tracker code (`app/.../data/track`), compiled into the desktop app
  unchanged (see "How the pieces fit"). Sign in under Settings > Tracking. OAuth trackers open
  their sign-in page in the built-in browser, which catches the `komikku://…-auth` redirect the
  trackers are registered with; "Other browser" takes a redirect pasted from any browser. Komga,
  Kavita and Suwayomi bind when an entry from their source is added to the library.
- **E-Hentai and ExHentai** are the Android app's built-in sources. Turn them on and sign in to
  ExHentai under Settings > E-Hentai (built-in browser, or the three cookies).
- **Merged entries and migration**: Manga > More. Merging searches all sources and creates the
  same merged entry as Android; migration copies or moves chapters, categories, tracking and
  other data by the Android flags.
- **Metadata and MangaDex**: entries from E-Hentai, MangaDex, NHentai, Pururin, 8Muses and LANraragi
  show the source's details and tags by namespace; "More info" opens every metadata field.
  Settings > MangaDex signs in and syncs follows; Settings > Data saver recompresses pages through
  Bandwidth Hero or wsrv.nl.
- **Site sign-in**: the globe button on a source opens its site in the built-in browser, to sign
  in or pass a check. The listing reloads with the new cookies when the window closes.

## Downloads, the local source and background work

The storage folder (the data folder unless moved in Settings) has the Android app's layout, so a
synced or copied folder works on both:

| Folder | What |
|---|---|
| `downloads/<source>/<manga>/<chapter>.cbz` | Downloads, named as Android names them (`Name (LANG)`, `Scanlator_Chapter`, optional URL hash) |
| `local/<manga>/` | The local source: chapters as image folders, CBZ, ZIP or EPUB; `ComicInfo.xml`, `details.json` and `cover.*` as on Android |
| `autobackup/` | Automatic backups, the newest four, every 12 hours by default |

- **Downloads** use the Android settings (CBZ or folder, sources and pages at once, URL hash,
  download new chapters after an update). Password-protected downloads are AES-256, AES-128 or
  standard zip encrypted CBZs, as on Android; the password lives in the OS secret store, and using
  the same password as on Android lets both apps read them. The queue survives a restart. The
  reader reads a downloaded chapter from disk.
- **Library updates** follow LibraryUpdateJob: the update categories, the skip rules (unread, not
  started, completed, outside the release period), five sources at once. Errors go to
  `<data>/reports/library-update-errors.txt`.
- **Background work** runs while the app is open, in place of WorkManager: the scheduled library
  update and automatic backup are checked every minute against when they last ran, so missed work
  runs soon after launch. Closing the window keeps Zoukan in the tray (Settings > General), from
  where it notifies about updates, downloads and failures.

## End-to-end tests

`EndToEndDownloadTest` is the Phase 5 gate: it installs an extension, adds three entries, updates the
library from the Updates tab, downloads chapters from an entry (as a CBZ, then an encrypted one),
reads the download, downloads every entry's newest chapter, reads from the local source and makes
an automatic backup. `EndToEndLocalTest` needs no network: it reads the local source, updates the
library and opens the new screens.

```bash
./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndDownloadTest"
./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndLocalTest"
```

`EndToEndReadTest` drives the real UI: it adds the Keiyoushi store, installs an extension, browses
its source, adds the first entry to the library and reads it in paged and webtoon mode. It needs
the network and a display, so it is skipped unless asked for:

```bash
./gradlew :desktop:test -Pzoukan.e2e=true --tests "*EndToEndReadTest"
./gradlew :desktop:test -Pzoukan.e2e=true -Pzoukan.e2e.package=eu.kanade.tachiyomi.extension.en.weebcentral --tests "*EndToEndReadTest"
```

It uses a throwaway data folder and saves screenshots to `desktop/build/reports/e2e/`.

The harness writes its report to `<data>/harness/reports/extensions-<platform>.md` and uses its own
profile in `<data>/harness`, so it never touches the app's extensions or database.

## Data folder

| Platform | Default |
|---|---|
| Windows | `%APPDATA%\Zoukan` |
| macOS | `~/Library/Application Support/Zoukan` |
| Linux (development only) | `~/.local/share/zoukan` |

`-Pzoukan.dataDir=<path>` (Gradle), `-Dzoukan.dataDir=<path>` (JVM) or `ZOUKAN_DATA_DIR` overrides it.

## Backups

Backups use the Android app's own code, moved into the shared `backup` module (the `.tachibk`
format, the creators and the restorers), so both apps read and write the same files. The desktop
app differs in two ways, so that a restore and backup loses nothing:

- It backs up the settings of every source that has settings, and the metadata of every entry
  that has it, also when the source's extension is not installed. Android only backs up installed
  sources'.
- A restore remembers the source names the backup lists.

`backup check` restores a backup into an empty, throwaway profile (its database key stays in the
profile, not the OS store), backs it up again with every option, and compares the two backups
field by field. `./gradlew :desktop:test` runs it on the synthetic full-field backups and on every
real export in `src/test/resources/backups/`; reports go to `desktop/build/reports/backup/`.

## How the pieces fit

| Piece | Where | Notes |
|---|---|---|
| Android APIs for extensions | `android-compat/` | Suwayomi's AndroidCompat (MPL-2.0), Koin replaced with Injekt |
| Dependency injection | `DesktopAppModule.kt`, `ZoukanRuntime.kt` | Registers what extensions and shared code read from Injekt |
| Database | `db/DesktopDatabase.kt` | SQLCipher 4 (sqlite-jdbc-crypt) with the app's SQLDelight schema, WAL; all database work runs on one thread (`DesktopAppModule`) |
| Database key | `secrets/` | Windows Credential Manager, macOS Keychain; a file only on Linux for development |
| Preferences | `DesktopPreferenceStore` (core/common) | Android SharedPreferences emulation, same keys and value types as the Android app |
| Backups | `backup/` (desktop), `:backup` (shared) | `DesktopBackups`, `BackupCheck`, `BackupComparison` |
| Cookies | `network/PersistentCookieStore.kt` | Shared by OkHttp and the built-in browser |
| Extensions | `extension-jvm/` | Keiyoushi's signed jars (store field 501); dex2jar only for stores without jars |
| Built-in browser | `browser/` | Chromium through jcefmaven, downloaded on first use into `<data>/browser` |
| Cloudflare | `CloudflareInterceptor` (core/common desktopMain) | Hidden WebView first, then a browser window (`--interactive`) |
| Android app code | `app-shared.txt`, `compat/` | Trackers, E-Hentai, merged source and some interactors are copied from `app/src/main/java` at build time (`syncAppShared`) and compiled as they are; `compat/` has desktop versions of the Android pieces they call |

## Known limits

- Converting APKs with dex2jar fails JVM bytecode verification for R8-minified APKs, which is every
  current Keiyoushi APK. Stores without jars only work for unminified extensions.
- Discord Rich Presence talks to the Discord app on the same computer, not to Discord's servers.
- Google Drive sync and the secure screen are Android only. See [PARITY.md](PARITY.md) for the
  other differences from Android.
- The local source reads RAR 5 chapters through the system's `tar` command (bsdtar, on macOS and
  Windows 10 and later); RAR 4, 7z and TAR open without it. RAR, 7z and TAR chapters are unpacked
  into `<data>/cache/archives` on first open (the last 8 are kept).
- Background work runs only while Zoukan runs (in the window or the tray), not as an OS service.
- The UI lives in the `desktop` module (`ui/`), not a shared `ui-shared` module:
  `presentation-core` is Android-only, so the screens are desktop Compose code.

## Debugging Chromium

`-Pzoukan.cefDebug=true` writes a verbose log to `<data>/browser/chromium.log`.
`-Pzoukan.cefArgs="--switch1 --switch2"` adds Chromium switches.
`--args="convert <apk> <jar>"` runs the APK conversion alone.
