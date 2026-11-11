# Releasing Zoukan desktop

Phase 7 of the desktop port: the installers, signing, the update check and crash logs. Every
check runs locally (there is no hosted CI): the Windows x64 steps on the Windows desktop, the
Apple Silicon steps on the Mac.

## Installers

| OS | Command | Output |
|---|---|---|
| Windows x64 | `.\gradlew.bat :desktop:packageMsi` | `desktop/build/packaging/installers/Zoukan-<version>-windows-x64.msi` |
| macOS arm64 | `./gradlew :desktop:packageDmg` | `desktop/build/packaging/installers/Zoukan-<version>-macos-arm64.dmg` |
| macOS arm64 | `./gradlew :desktop:notarizeDmg` | the same DMG, notarized and stapled |
| either | `./gradlew :desktop:createDistributable` | the app folder alone (`desktop/build/packaging/app`) |

Each installer carries its own Java runtime, built with jlink from every module of the packaging
JDK (extensions are arbitrary code and may use any module). The packaging JDK is
`-Pzoukan.packageJdk=<JDK home>` or `ZOUKAN_PACKAGE_JDK`, else the JDK running Gradle; it must be a
full JDK 21 with `jmods/` (the plan's choice is JetBrains Runtime 21, the `jbrsdk` download;
Temurin 21 works too).

- **Windows**: the MSI installs for the current user under `%LOCALAPPDATA%` without an
  administrator prompt, adds Start menu and desktop shortcuts, and upgrades an older Zoukan in
  place (fixed upgrade code). jpackage needs WiX Toolset 3; when `candle.exe` is not on the PATH
  the build downloads WiX 3.14 once into `desktop/build/packaging/wix`.
- **macOS**: the DMG holds `Zoukan.app` (bundle id `app.zoukan.desktop`) to drag to Applications.

Zoukan's version is `zoukanVersion` in `desktop/build.gradle.kts` (MAJOR.MINOR.PATCH, MAJOR from 1
to 255). It is separate from the Android app's version, which extensions still see.

Compose Multiplatform's Gradle plugin is not used: its `compose` extension clashes with the
upstream `compose` version catalog. `desktop/packaging.gradle.kts` calls jlink and jpackage itself.

## Signing

Without these settings the build works and is unsigned, and says so. Each is an environment
variable or the Gradle property in brackets (`~/.gradle/gradle.properties` keeps them out of the
repository).

### Windows (Authenticode, signtool from the Windows SDK)

| Setting | What |
|---|---|
| `ZOUKAN_WINDOWS_SIGN_PFX` (`zoukan.windows.signPfx`) | A code signing certificate as a .pfx file |
| `ZOUKAN_WINDOWS_SIGN_PASSWORD` (`zoukan.windows.signPassword`) | Its password |
| `ZOUKAN_WINDOWS_SIGN_THUMBPRINT` (`zoukan.windows.signThumbprint`) | Or: the SHA-1 thumbprint of a certificate in the user's store (also hardware tokens and cloud signing that install a certificate) |
| `ZOUKAN_WINDOWS_SIGNTOOL` | signtool.exe, when it is not in the Windows SDK or on the PATH |
| `ZOUKAN_WINDOWS_TIMESTAMP_URL` | Timestamp server, default `http://timestamp.digicert.com` |

`Zoukan.exe` is signed before it goes into the MSI, then the MSI is signed. An unsigned MSI
installs, but SmartScreen warns about an unknown publisher.

### macOS (Developer ID and notarization)

| Setting | What |
|---|---|
| `ZOUKAN_MAC_SIGN_IDENTITY` (`zoukan.mac.signIdentity`) | `Developer ID Application: Name (TEAMID)`, in the login keychain |
| `ZOUKAN_MAC_KEYCHAIN` (`zoukan.mac.keychain`) | Another keychain, optional |
| `ZOUKAN_NOTARY_PROFILE` (`zoukan.mac.notaryProfile`) | A notarytool profile: `xcrun notarytool store-credentials zoukan --apple-id … --team-id …` |
| `ZOUKAN_NOTARY_APPLE_ID`, `ZOUKAN_NOTARY_PASSWORD`, `ZOUKAN_NOTARY_TEAM_ID` | Or these three; the password is an app-specific password, never the account's |

With an identity set, the build signs the native libraries inside the jars (SQLite, JNA, Skia and
the rest; notarization rejects unsigned code anywhere in the app), then the app with the hardened
runtime and `packaging/macos/entitlements.plist` (JIT, unsigned executable memory and libraries
loaded at run time, which a JVM and Chromium need), then the DMG. `notarizeDmg` submits the DMG,
waits, staples the ticket and asks Gatekeeper. Without an identity the app is ad hoc signed:
it runs on the Mac that built it, and a downloaded copy is blocked the first time until System Settings > Privacy & Security > Open Anyway.

The database key is a Keychain item that only the app that created it may read without asking.
A Developer ID build keeps its identity from one version to the next, so the item stays readable.
An ad hoc build is a new identity every time, so the first start of each new ad hoc build on a
Mac that already has a Zoukan profile shows "Zoukan wants to use your confidential information
stored in Zoukan" before the window opens; Zoukan waits there until the user answers. Always Allow
(the login password) lets that build in; Deny stops Zoukan with a crash report (the Keychain refused the key: status -128), and
the database is left as it was.

## Clean-install checks (the Phase 7 gate)

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File desktop\packaging\check\install-check-windows.ps1
```

```bash
# macOS
desktop/packaging/check/install-check-macos.sh
```

Each builds the installer, removes an installed Zoukan, installs it the way a user would (silent
per-user MSI; DMG copied to a fresh folder), checks shortcuts, signatures, the hardened runtime and
what Gatekeeper says about a downloaded copy, runs the installed app's self-test, uninstalls, and
writes `desktop/build/reports/install/install-check-<os>.md`. `--skip-build` / `-SkipBuild` reuses
the last installer; `--keep-installed` / `-KeepInstalled` leaves it installed.

The self-test is also a command of the installed app: `Zoukan selftest --ui --network --report
<file.md>` (on Windows `Zoukan.exe`, which has no console, so read the report). It uses a throwaway
profile and checks the bundled runtime and its modules, the OS secret store, the encrypted
database, extension loading, crash logs, the window (Skia's native code) and HTTPS to GitHub.

## Publishing a release

1. Raise `zoukanVersion` in `desktop/build.gradle.kts` and merge.
2. On the Windows desktop: run the Windows install check, keep
   `Zoukan-<version>-windows-x64.msi`.
3. On the Mac: run the macOS install check with signing and notarization set, keep
   `Zoukan-<version>-macos-arm64.dmg`.
4. Create a GitHub release on `JasperNg/zoukan` tagged `desktop-v<version>` with both files
   attached, for example `gh release create desktop-v1.0.0 Zoukan-1.0.0-windows-x64.msi
   Zoukan-1.0.0-macos-arm64.dmg --title "Zoukan 1.0.0" --notes "…"`. Mark test builds as
   pre-releases.

## Update check

Settings > About checks the GitHub releases of `JasperNg/zoukan` (`-Pzoukan.releaseRepo=owner/name`
at build time changes it) for the newest `desktop-v*` tag above the running version, once a day
while Zoukan runs and on demand. It notifies once per new version, downloads the installer for the
platform (checking the SHA-256 GitHub lists) into `<data>/updates`, and opens it: on Windows the
MSI upgrades Zoukan, which quits first; on macOS the DMG opens to drag Zoukan to Applications.

While the repository is private, the check needs a GitHub token: a fine-grained personal access
token for that repository with read-only Contents access, entered under Settings > About >
GitHub token and kept in the OS secret store. Without one the check says it found no releases.

### What an update must pass

Before the installer is opened, and again when Install is clicked:

- GitHub must list a SHA-256 for the asset, and the download must match it.
- The installer must be signed by the publisher of the running app: the same Authenticode
  certificate subject on Windows (`Get-AuthenticodeSignature` must say `Valid`), the same Developer
  ID team on macOS (`codesign --verify --strict`). A renewed certificate with the same subject
  passes. **A certificate with another subject, or another Apple team, does not**: users on the
  old publisher's builds must then install the new version by hand once.
- A build that is not signed itself has no publisher to compare with and checks only the
  SHA-256, so unsigned builds keep updating as before.

## Chromium's checksum

The built-in browser's Chromium (jcefmaven's `jcef-natives-<platform>-<tag>.jar`) is downloaded
on first use and installed only when its SHA-256 is the one in
`src/main/resources/jcef-natives.sha256`. After changing the `jcef` or `jcefmaven` version in
`gradle/zoukan.versions.toml`, download the new jars for windows-amd64, macosx-arm64 and
linux-amd64 from Maven Central, compare them with the same files on jcefmaven's GitHub release,
and replace the lines. `ChromiumNativesTest` fails until they are there.

## One window per data folder

Opening Zoukan while it runs (its icon, the Start menu, the Dock; with "keep running in the tray"
that is how the window comes back) brings the running window to the front instead of starting a
second app on the same library: the first instance holds `<data>/zoukan.lock` and listens for
that request on a loopback port with a random token (`<data>/zoukan.port`).

## Logs and crash logs

| What | Where |
|---|---|
| Log | `<data>/logs/zoukan.log`, 5 MB, three older files kept |
| Crash reports | `<data>/crashes/crash-*.txt`: versions, OS, Java, the stack trace and the end of the log |
| Errors | `<data>/crashes/error-*.txt`: an uncaught exception on a background thread; the app went on |
| Unclean exits | `<data>/crashes/unclean-exit-*.txt`, or `jvm-crash-hs_err_pid*.log` when the JVM itself crashed |

A crash in the window or while starting writes a report, shows a dialog with its path and closes
Zoukan. Each Zoukan process leaves a `<data>/logs/.running-<pid>` marker that its shutdown removes;
when the next start finds one whose process is gone (killed, out of memory, a JVM crash), it says
so. Markers of processes still running, such as a command-line run on the same folder, are left
alone. Settings > About opens
both folders. The 30 newest crash reports and the 30 newest error reports are kept.
