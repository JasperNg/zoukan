# Desktop threat model

The security posture of the Windows and macOS app, as a completed security review found it. It is
the record of what is verified before what runs, and what is still trusted on faith, so a later
change can be checked against it. Everything here was read in the code, not asserted; file
references are repository paths.

The short version: code the app runs (extensions, updates, Chromium) is signature- or
hash-verified against a pinned identity; data the app reads (archives, XML, filenames) is parsed
in-process with path-traversal and external-entity defenses; secrets at rest are encrypted under a key
that lives in the OS secret store. The largest accepted risk is inherited from the extension
model itself: an extension runs with the user's full privileges, in-process, and the store that
names its signing key is trusted over TLS alone.

## Trust boundaries

What the app accepts from outside its own code, split by where it comes from.

Remote:

- Manga sites and CDNs: HTML, JSON, images. Parsed in-process by extension code.
- Extension stores: the index (JSON/protobuf over https), the extension list, and the code jars
  and APKs. The index self-declares the signing key its packages must carry.
- GitHub releases: the update check, the release JSON, the installer assets.
- jcefmaven mirrors (Maven Central, GitHub): the Chromium natives package.
- OAuth trackers: the sign-in pages, and the `komikku://` redirect the app catches.

Local:

- Extension packages: installed jars and APKs under the extensions folder, run in-process.
- Files the user opens: CBZ/ZIP/EPUB/RAR/7z/TAR chapters, `.tachibk` backups, ComicInfo.xml.
- Same-user processes: anything else running as the user, including malware.
- The OS secret store: Windows Credential Manager / macOS Keychain, holding the keys everything
  else is encrypted under.

## Extensions

An extension is third-party Java code. Everything before it runs is verified; what it does once it
runs is not contained at all.

Verified and trusted:

| Control | Where |
|---|---|
| JAR signature: every entry signed, all by one certificate; only the manifest and the signature files themselves (`META-INF/MANIFEST.MF`, `*.SF`, `*.RSA`, `*.DSA`, `*.EC`, `SIG-*`) are exempt, so service files and multi-release classes under META-INF must be signed too | `extension-jvm/src/main/kotlin/zoukan/extension/ExtensionSignatures.kt` (`verifyJar`, `isSigningRelated`) |
| APK signature: v1 + v2 + v3 via apksig, as Android's package manager | same file (`verifyApk`) |
| The signature is re-checked from the file on disk at every load, not remembered from install | `extension-jvm/.../DesktopExtensionLoader.kt` (`loadOrThrow`) |
| What runs is what was checked: the jar is read into memory once, its signature (or hash) is checked on those bytes, and the class loader defines classes and serves resources from the same bytes, never reopening the file. No jar stays open, so on Windows an uninstall or same-version reinstall is not blocked by a file lock | `extension-jvm/.../ExtensionClassLoader.kt` (`JarContents`, `ExtensionClassLoader`), `DesktopExtensionLoader.loadOrThrow` |
| A jar converted from an APK runs only while it matches the SHA-256 recorded at conversion, which is kept in the app's preferences (app-state key `converted_jar_sha256_<package>`, encrypted at rest under the OS-store key), not next to the jar; a missing or stale hash (including every install from before this change, which kept a `.sha256` file beside the jar that is now ignored and removed) converts the signature-verified APK again rather than trusting or deleting the jar | `extension-jvm/.../ConvertedJarHashes.kt`, `DesktopExtensionLoader.verifiedConvertedJar` |
| Installs and updates are staged: an APK is converted into a temporary file and moved into place only after the conversion worked, and an update whose new files fail to load is removed again when an older version was loaded and the version codes differ, so the installed, working version stays (a reinstall of the same version, or a package that was not loaded, keeps the new files and reports the error). The converted jar's hash is recorded only once a load succeeds, so a failed update does not replace the working version's hash | `DesktopExtensionInstaller.installApk`, `DesktopExtensionManager.installExtension`, `DesktopExtensionLoader.loadOrThrow` |
| No downgrades: the file's version must be the one the store lists, and not older than the installed version | `DesktopExtensionInstaller.checkManifest` |
| Install, update, uninstall and trust of one package run one at a time, so an uninstall cannot be undone by an install that was still running | `DesktopExtensionManager` (`locked`) |
| Updates pinned to the installed signer; a differently-signed file is refused before it replaces anything | `extension-jvm/.../DesktopExtensionInstaller.kt` (`checkSigner`, `requiredSigner`) |
| A rival store cannot offer an update for another store's package; tested | `DesktopExtensionManager.chooseOffer`, `desktop/src/test/kotlin/zoukan/desktop/extension/ExtensionStoreSafetyTest.kt` |
| Manifest package name must match the store's offer, then validate | `DesktopExtensionInstaller.checkManifest`, `ExtensionManifest.kt` |
| Store index, list and code URLs are https-only; localhost exempt for a local test store. A redirect from https to plain http is refused, not followed | `data/src/commonMain/kotlin/mihon/data/extension/service/ExtensionStoreService.kt` (`requireSecureStoreUrl`, `awaitSecureStoreResponse`), also used by the installer's download |
| Per-user trust grants (package + version + signature), revocable | `extension-jvm/.../ExtensionTrust.kt` (`trust`, `revokeAll`). The grants are an app-state preference (`trusted_extensions` under the app-state prefix), so they stay on this computer: backups leave them out (`PreferenceBackupCreator`) and restores drop them (`DesktopBackups.withSafePreferences`); an extension trusted by hand is trusted again after moving a library |
| A restored extension store that is new, or whose signing key changed, is added only after the user confirms it | Settings restore: `newExtensionStores` with the confirmation in `ui/settings/BackupScreens.kt`. Data sync (`compat/SyncManager.kt`) and the command line `backup restore` (`Main.kt`) drop those stores instead and say so (`DesktopBackups.withoutNewExtensionStores`) |
| No debug-build auto-trust on desktop: an unsigned-for-development app trusts exactly what it would trust installed | absence by construction; `ExtensionTrust` has no debug path |

Residual risk:

1. **No sandbox.** An extension runs in-process with the user's full account privileges. The
   loader's `ChildFirstURLClassLoader` is effectively system-first, so extension code reaches
   every app class: the SQLCipher database, the cookie store, JNA and the native APIs. Android
   contained this in a per-app UID sandbox; a desktop has nothing equivalent, and the port keeps
   the Android model deliberately — the same model as Suwayomi-Server. A malicious or turncoat
   extension store is a whole-user compromise behind one ordinary "install extension" consent.
2. **The store index is its own root of trust.** The index names the signing key its packages
   carry (`NetworkExtensionStore.signingKey`) and is fetched over TLS only — not pinned, not
   signed. A store operator, a store-host compromise, or a stolen GitHub account rotates the
   trust root and every package it publishes installs. The same applies to Mihon and Komikku on
   Android.
3. **Hostile APKs are parsed in-process before the trust decision.** For a store that publishes
   only APKs, dex2jar translates dex to JVM bytecode, Suwayomi's ASM-based `BytecodeEditor`
   rewrites it, and the manifest and assets are read — all before any signature comparison, which
   happens at load. dex2jar is effectively unmaintained; this is pre-consent exposure to its
   parsers. Downloads have no size cap. Stores with ready-made jars (Keiyoushi, the norm) skip
   this path entirely.
4. **A converted jar can be swapped after install** by a same-user process, because the APK's
   signature does not cover it. (Mitigated: its hash is recorded at conversion in the encrypted
   preferences, not beside the jar, and the loader runs the very bytes it hashed; a replaced jar is
   converted again from the verified APK.) What is left: a same-user process that can read the
   OS-store key can rewrite the preferences, as residual risk 1 under Secrets at rest says; and
   the preferences loader still accepts a plain, unencrypted file (the migration path from before
   encryption, `JavaSharedPreferences`), so such a process can replace the whole preferences file
   without the key, losing the user's settings. The same applies to the trust grants above.
   There is also a moment between a conversion and the read of its result in which a swap would
   be hashed as genuine.

## Updater

The updater replaces the whole app. It checks a download three ways before running it.

Verified and trusted:

| Control | Where |
|---|---|
| Release JSON and downloads over TLS to `api.github.com`; asset names limited to a safe pattern | `desktop/src/main/kotlin/zoukan/desktop/update/UpdateChecker.kt` (`request`, `SAFE_NAME`) |
| SHA-256 from GitHub's `digest` field checked while streaming; refused outright when GitHub lists none | `UpdateChecker.downloadTo`; the refusal in `desktop/.../update/Updates.kt` (`states.download`) |
| Authenticode certificate of the installer compared with the running executable's on Windows; Developer ID team on macOS; rejected downloads are deleted | `desktop/.../update/InstallerSignature.kt` (`verify`), invoked in `Updates.download` and again in `Updates.install` |
| The signature is re-verified immediately before the installer runs — it sat in a folder other programs can write to | `Updates.install` |
| The GitHub token lives in the OS secret store, never in a file | `UpdateChecker.token` (`TOKEN_KEY` in `SecretStore`) |

Residual risk:

1. The publisher comparison is a subject string on Windows, not a certificate thumbprint.
   (Hardened: thumbprint comparison, failing closed when the running app's thumbprint cannot be
   determined.)
2. The GitHub token is attached to whatever asset URL the release JSON names — a compromised
   repository or token holder can point the download at any https host. The digest and publisher
   checks still bound that, but a build with no publisher to compare (an unsigned build) rests on
   the checksum alone, as the `InstallerSignature.Verdict.Unchecked` case says in so many words.

## Web and embedded Chromium

The built-in browser (JCEF) solves Cloudflare challenges and hosts site and tracker sign-ins.

Verified and trusted:

| Control | Where |
|---|---|
| Start address must be http/https with a host; `file:` and other schemes refused | `desktop/.../browser/BrowserWindow.kt` (start-URL check) |
| No JavaScript-to-Java bridge exists; the only channels out of a page are the cookie sync and the main-frame URL intercept | absence by construction in `BrowserWindow` |
| Links opened in the system browser are http/https with a host only — `javascript:`, `file:` and custom schemes never reach the OS handler | `desktop/.../app/ExternalLinks.kt` (`webUri`) |
| Chromium natives downloaded only with the SHA-256 pinned for that exact JCEF build and platform (missing pin = no download), over https mirrors, installed atomically, half-installed state deleted | `desktop/.../browser/ChromiumNatives.kt` (`ensureInstalled`, `pinnedSha256`, the `jcef-natives.sha256` pin file) |

Residual risk:

1. **OAuth redirect capture without origin binding.** A tracker sign-in catches `komikku://anilist-auth`
   (and the other trackers' redirects) wherever the sign-in window happens to navigate
   (`TrackerLogin.isRedirect`): any page in that window can bounce to the app scheme. There is no
   initiator-origin check, so a page the victim is lured to sign in on can complete a login CSRF —
   the victim's Zoukan library then silently syncs to the attacker's tracker account. Token theft
   is not possible: the code in the redirect is the attacker's own. Inherited from Android's
   custom-scheme design, whose registered redirects the trackers require; fixing it means the
   trackers move to loopback or https redirects first.
2. Chromium inside JCEF lags Chromium releases; the sign-in window is a full-JS browser pointed at
   remote content, with the ordinary exposure that implies.

## Local files

Archives, backups and XML the app reads; names it writes.

Verified and trusted:

| Control | Where |
|---|---|
| Download filenames sanitized as Android builds them: control chars, `/`, `\`, `:`, drive-colon parts removed | `desktop/.../download/FileNames.kt` (`buildValidFilename`) |
| CBZ entries are generated page names only, on write and on read; a zip entry name never becomes a path | `desktop/.../download/Cbz.kt`, `ChapterDownloader.kt` |
| ComicInfo.xml parsing refuses doctype declarations, so no XXE | `desktop/.../download/ComicInfoXml.kt` |
| EPUB XML allows a doctype (XHTML carries one) but fetches nothing outside the file: secure processing on, external general and parameter entities off, external DTDs and schemas not loaded, no XInclude, entity references not expanded | `desktop/.../local/LocalFormat.kt` (`xml`) |
| Archive entry names can't escape the unpack folder: absolute paths, `..`, and drive-colon parts refused | `desktop/.../local/Archives.kt` (`write`) |
| Unpacking is capped (per entry, per archive total, per entry count; the system tar is watched while it runs and killed over budget) and symlink entries are skipped, in every path | `Archives.kt` (`Budget`, `Limits`, `systemTar`) (hardened) |
| RAR/7z/TAR read in-process by junrar and Commons Compress; anything they cannot read falls back to the system `tar` with a five-minute timeout | `Archives.kt` (`unpack`) |
| Backup restore refuses a file that is not a backup before it is applied | `desktop/.../backup/RestoreGate.kt` |

Residual risk:

1. The RAR 5 and odd-7z fallback hands the archive to the system `tar` (bsdtar/libarchive),
   which has CVE history in RAR5 and 7z parsing; it runs as the user on hostile data. Java-side,
   junrar and Commons Compress parse the same hostile data in-process. The caps and symlink-skip
   bound the blast radius; they do not remove memory-safety exposure in those parsers.
2. A crafted archive can still cost CPU and disk up to the caps (a budget of gigabytes), and a
   same-user process can tamper with anything in the cache folders.

## Secrets at rest

What touches the disk unencrypted is deliberately small.

Verified and trusted:

| Control | Where |
|---|---|
| Database always encrypted (SQLCipher 4 scheme, SQLite3MultipleCiphers) under a random 256-bit raw key | `desktop/.../db/DesktopDatabase.kt` (`createDriver`, `properties`) |
| The database key lives in the OS secret store, never next to the file; an existing database with a missing key refuses to open (fail-closed) | `DesktopDatabase.databaseKey`, `KeyMissingException` |
| Preferences, cookies and session files encrypted at rest with AES-256-GCM under a second random OS-store key | `desktop/.../secrets/FileCipher.kt` |
| Windows: Credential Manager generic credentials, per-user, non-roaming; macOS: login Keychain generic passwords | `desktop/.../secrets/WindowsCredentialStore.kt`, `MacKeychainStore.kt` |
| Session cookies never persisted: the app's cookie store is in-memory (`java.net.CookieManager`), synced with Chromium only while windows are open | `android-compat/.../webkit/CefCookieSync.kt` |
| App-lock password: PBKDF2-HMAC-SHA256, 210,000 iterations, 16-byte per-password salt, constant-time comparison | `desktop/.../security/AppLock.kt` (`hash`, `verify`) |

Residual risk:

1. **Same-user malware can read the database key** from Credential Manager or the Keychain; the
   OS secret store protects against other users and against disk theft, not against code running
   as the same user. This is inherent to desktop secret stores.
2. On Linux — a development target only — secrets go to a plaintext file restricted to 0600 after
   the write (`FileSecretStore.set`), so a same-user process watching the folder can win a
   write-before-chmod race; and Chromium's own cookie DB on disk uses CEF's well-known static key.
3. App lock has no attempt rate limit; offline guessing is bounded only by PBKDF2 cost. And
   `AppLock.verify` fails open when the stored hash is missing (returns unlocked rather than
   locked-down) — a known nit, harmless while the lock is enabled and the hash exists. When the
   secret store itself cannot be read and the lock is on, `AppLock.isEnabled` fails closed: the
   app stays locked.

## Processes

Everything the app executes outside its own JVM.

Verified and trusted:

| Control | Where |
|---|---|
| Every external process is launched as an argv array through `ProcessBuilder` — no shell string is ever built, so no manga title, folder name or URL can inject a command | `InstallerSignature.kt` (PowerShell, with file paths passed through the environment, not the command line), `Updates.kt` (`msiexec`, `open`), `Archives.kt` (`tar`), `PowerStatus.kt` (`pmset`), `ReaderEffects.kt` (`caffeinate`), `ReducedMotion.kt` |
| URL opening goes through `java.awt.Desktop.browse`, never a shell | `ExternalLinks.browse` |

No shell out means the classic injection class — a chapter titled `foo"; rm -rf ~; "` — has no
sink. Residual risk is the usual one: these processes run with the user's privileges, on
arguments the app controls but a hostile archive's *presence* on disk can influence (the tar
fallback's input file is user- or source-chosen content, parsed by libarchive as above).

## Accepted risks

| Risk | Why accepted | What would change it |
|---|---|---|
| Extensions run in-process with full user privileges | The Android extension model, kept on purpose; the whole extension ecosystem (Keiyoushi, Komikku's) depends on it, and Suwayomi-Server makes the same trade | Run extensions out-of-process with an IPC surface; a large, ongoing engineering effort |
| Store index self-declares its signing key over TLS | Same design as Mihon/Komikku; a curated or pinned root would break user-added stores, which the feature exists to allow | Signed indexes or an app-shipped list of known store keys, with user-added stores still allowed but flagged |
| dex2jar/ASM parse hostile APKs in-process before the trust decision | Only stores without jars reach it; the dominant store (Keiyoushi) publishes jars; dex2jar is unmaintained, so "fix the parser" is not available | Drop the APK path (jars-only stores), or convert in a throwaway subprocess/sandbox with a download size cap |
| System tar (libarchive) reads RAR 5 and odd 7z archives | RAR 5 has no maintained pure-Java reader; without tar those chapters cannot open at all | The caps and symlink-skip shrink it (hardened); a pure-Java RAR 5 reader would remove it |
| OAuth login CSRF from unbound `komikku://` redirects | The trackers' app registrations only accept the custom scheme; Android inherits the same flaw | Trackers move to loopback or https redirects, then the window can bind the redirect to the page it was opened from |
| Same-user malware reads secret-store keys | OS design; no desktop API grants per-read prompts or hardware binding for generic credentials | OS-level change; the app can only shrink what is at rest, which it already does |
| Updater follows the release JSON's asset URL with its token | Private-repo support needs it; GitHub hosts the JSON and the digest | Pin the download host, or proxy releases through a host the token is not sent to |
| Build dependencies have no project-wide `verification-metadata.xml` | Upstream has none, and a partial file fails the build; Maven Central, Google and the Gradle plugin portal serve fixed versions over TLS. The one artifact from a plain GitHub repository, Suwayomi's `android-jar`, is pinned to a commit (`settings.gradle.kts`) and checked against a SHA-256 before anything compiles against it (`android-compat`'s `verifyAndroidJar`) | A complete `gradle/verification-metadata.xml` for every dependency, kept current on each update |
| App-lock verifies fail-open with no rate limit | The lock is a shoulder-surfer control, not a decryption boundary; PBKDF2 at 210k iterations bounds offline guessing | Make `verify` fail closed when the lock is enabled, and add a delay between attempts |
