# Desktop threat model

The security posture of the Windows and macOS app, as a completed security review found it. It is
the record of what is verified before what runs, and what is still trusted on faith, so a later
change can be checked against it. Everything here was read in the code, not asserted; file
references are repository paths.

The short version: code the app runs (extensions, updates, Chromium) is signature- or
hash-verified against a pinned identity; data the app reads (archives, XML, filenames) is parsed
in-process with path-traversal and external-entity defenses; secrets at rest are encrypted under a key
that lives in the OS secret store. What the app sends is kept to what a page or source needs: DNS
over HTTPS by default, the OS proxy followed, an HTTP cache in memory only, and a built-in browser that keeps
no history and blocks third-party ad and tracker hosts. The largest accepted risk is inherited
from the extension model itself: an extension runs with the user's full privileges, in-process,
and the store that names its signing key is trusted over TLS alone.

## Trust boundaries

What the app accepts from outside its own code, split by where it comes from.

Remote:

- Manga sites and CDNs: HTML, JSON, images. Parsed in-process by extension code.
- Extension stores: the index (JSON/protobuf over https), the extension list, and the code jars
  and APKs. The index self-declares the signing key its packages must carry.
- GitHub releases: the update check, the release JSON, the installer assets.
- jcefmaven mirrors (Maven Central, GitHub): the Chromium natives package.
- OAuth trackers: the sign-in pages, and the `komikku://` redirect the app catches.
- The DNS over HTTPS provider (Quad9 unless the user chose another or turned it off): the answer
  to every public name the app looks up.
- The proxy set in the OS, when there is one: it carries, and resolves, every connection.
- Block lists the user adds by address: plain-text host lists, downloaded weekly (sooner after a refresh in which none came through), only while "Block ads and trackers" is on.

Local:

- Extension packages: installed jars and APKs under the extensions folder, run in-process.
- Files the user opens: CBZ/ZIP/EPUB/RAR/7z/TAR chapters, `.tachibk` backups, ComicInfo.xml.
- Same-user processes: anything else running as the user, including malware.
- The OS secret store: Windows Credential Manager / macOS Keychain, holding the keys everything
  else is encrypted under.

### Checked by

The test classes that check each boundary's controls, and what no test checks yet. A row's
first cell is the boundary's name as the list above gives it (before the colon, without the
part in brackets). `ThreatModelCoverageTest` checks that every boundary has a row and that every
class named here exists and has tests; it does not check what the tests assert, so keep the
classes to ones that test the boundary.

| Boundary | Checked by | Gap |
|---|---|---|
| Manga sites and CDNs | `zoukan.desktop.compat.ImageDecoderTest`, `zoukan.desktop.compat.BitmapFactoryCompatTest`, `zoukan.desktop.network.CloudflareInterceptorTest`, `zoukan.desktop.download.ChapterDownloaderTest` | Only the app's own handling is tested (oversized and broken images, large error bodies, HTML served as a page). Extension code parses pages in-process and nothing contains it (Extensions, residual risk 1) |
| Extension stores | `zoukan.desktop.extension.ExtensionStoreSafetyTest`, `zoukan.extension.ExtensionVerificationTest`, `zoukan.extension.DesktopExtensionManagerTest`, `zoukan.extension.ExtensionNsfwAndStoreTest` | The index's self-declared signing key is trusted over TLS alone (an accepted risk) |
| GitHub releases | `zoukan.desktop.update.ReleaseSignatureTest`, `zoukan.desktop.update.UpdatesDownloadTest`, `zoukan.desktop.update.InstallerSignatureTest`, `zoukan.desktop.update.UpdateCheckerTest` | |
| jcefmaven mirrors | `zoukan.desktop.browser.ChromiumNativesTest` | The pin's presence is tested, not its use: no test feeds a natives package whose SHA-256 does not match and checks that it is refused |
| OAuth trackers | `zoukan.desktop.track.TrackerLoginTest`, `zoukan.desktop.app.ExternalLinksTest` | The redirect is not bound to the page that sent it (Web and embedded Chromium, residual risk 1) |
| The DNS over HTTPS provider | `zoukan.desktop.network.AppDnsResolverTest`, `zoukan.desktop.network.DohClientsTest`, `zoukan.desktop.network.DnsLeakSourceScanTest`, `zoukan.extension.ExtensionHttpRewriteTest` | |
| The proxy set in the OS, when there is one | `zoukan.desktop.network.SystemProxySourceScanTest`, `zoukan.desktop.network.DohProxyTest`, `zoukan.desktop.network.PacProxyTest` | |
| Block lists the user adds by address | `zoukan.desktop.browser.blocking.UserBlockListsTest`, `zoukan.desktop.browser.blocking.ContentBlockingTest`, `zoukan.desktop.browser.blocking.BlockListUpdaterTest` | |
| Extension packages | `zoukan.extension.ExtensionVerificationTest`, `zoukan.extension.DesktopExtensionLoaderTest`, `zoukan.extension.PreferenceConvertedJarHashesTest`, `zoukan.extension.ApkConverterTest` | The trust grants are tested only for being left out of backups (`ExtensionTrustTest`); what an extension does once it runs is not contained (an accepted risk) |
| Files the user opens | `zoukan.desktop.local.ArchivesTest`, `zoukan.desktop.local.LocalSourcePathTest`, `zoukan.desktop.local.EpubExternalEntityTest`, `zoukan.desktop.property.ComicInfoEntityTest`, `zoukan.desktop.backup.BackupCodecTest` | |
| Same-user processes | `zoukan.desktop.DataFolderPrivacyTest`, `zoukan.desktop.secrets.EncryptedFileSystemTest`, `zoukan.desktop.backup.BackupPermissionsTest`, `zoukan.desktop.app.SingleInstanceTest` | No test checks that the installer is verified again just before it runs, so a file another program swaps after the download and before the install is not known to be caught |
| The OS secret store | `zoukan.desktop.db.DesktopDatabaseKeyTest`, `zoukan.desktop.secrets.FileCipherTest`, `zoukan.desktop.download.DownloadSafetyTest`, `zoukan.desktop.secrets.SecretStoreFailureTest`, `zoukan.desktop.secrets.FileSecretStoreContractTest`, `zoukan.desktop.secrets.MacKeychainStoreContractTest`, `zoukan.desktop.secrets.WindowsCredentialStoreContractTest` | The Keychain contract test is opt-in (`ZOUKAN_KEYCHAIN_TESTS=true`, set on CI's macOS runner), and the Credential Manager one skips in a logon with no Credential Manager (SSH) |

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
| Installs and updates are staged: an APK is converted into a temporary file and moved into place only after the conversion worked, and an update whose new files fail to load is removed again when an older version was loaded and the version codes differ, so the installed, working version stays (a reinstall of the same version, or a package that was not loaded, keeps the new files and reports the error). The converted jar's hash is recorded after its manifest has been read and validated, not after the extension's classes load: an extension the user has not trusted yet, or an NSFW one that is switched off, still gets its hash recorded. A failed conversion or manifest check records nothing, so it does not replace the working version's hash. The record is `versionCode:sha256` under the package name; it is not tied to a particular APK file | `DesktopExtensionInstaller.installApk`, `DesktopExtensionManager.installExtension`, `DesktopExtensionLoader.loadOrThrow` |
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
   loader, `ExtensionClassLoader` (`extension-jvm/.../ExtensionClassLoader.kt`), asks the app's
   class path first and the extension's own jar second, so extension code reaches
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
   parsers. Downloads have no size cap. Stores with ready-made jars (the norm) skip
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

The updater replaces the whole app. It checks a download before running it, as the controls
below say. The installers are not OS-signed (no Authenticode certificate, no Developer ID or
notarisation: a project decision), so what protects an update is the release signature and the
digest, not an OS publisher check.

Verified and trusted:

| Control | Where |
|---|---|
| Release JSON and downloads over TLS to `api.github.com`; asset names limited to a safe pattern. The token is sent only to `https://` addresses on the API host: an asset address anywhere else is refused before a request is made. The checker uses the app's plain client: the app's DNS, but no source cookies, no cache, no interceptors (so no Cloudflare window) and OkHttp's own user agent | `desktop/src/main/kotlin/zoukan/desktop/update/UpdateChecker.kt` (`request`, `assetRequest`, `SAFE_NAME`); `Updates.gitHubChecker` (`NetworkHelper.plainClient`) |
| A detached Ed25519 release signature, `<installer name>.sig`, made by the release job with a key held as a repository secret that only that job reads (`ZOUKAN_RELEASE_SIGNING_KEY`), checked against the public key compiled into the app (`desktop/src/main/resources/zoukan-release-key.pem`). It covers the installer's name and the SHA-256 the app computes itself, so GitHub's digest is not part of the trust. A missing or wrong signature, or a packaged build without the key, is refused (fails closed), and the files are deleted. Releases made before it have no signature, and apps from before it do not check one | `desktop/.../update/ReleaseSignature.kt` (`verify`), invoked in `Updates.download` and again in `Updates.install`; `.github/workflows/desktop-build.yml` (`release` job) |
| SHA-256 from GitHub's `digest` field checked while streaming; refused outright when GitHub lists none | `UpdateChecker.downloadTo`; the refusal in `desktop/.../update/Updates.kt` (`states.download`) |
| Only when the running app is itself OS-signed (release builds are not): the installer's signer must match it, by the SHA-256 of the signer certificate's public key on Windows (a renewal that reuses the key passes; the thumbprint is not compared) and by Developer ID team on macOS. A build that says it shipped signed but has no readable signature fails closed. For an unsigned release this check is `Unchecked` and adds nothing | `desktop/.../update/InstallerSignature.kt` (`verify`), invoked in `Updates.download` and again in `Updates.install` |
| The signatures are re-verified immediately before the installer runs — it sat in a folder other programs can write to. The installer is first copied into a new folder of its own (owner-only on macOS and Linux), the copy is checked, and that same copy is started by the tool's absolute path (`%SystemRoot%\System32\msiexec.exe` on Windows, `/usr/bin/open` on macOS), never by a bare name looked up on `PATH` | `Updates.install`, `Updates.installerCommand` |
| The GitHub token lives in the OS secret store (on Linux, which is for development only, a file readable only by the user), never in the preferences or any other file; Remove deletes it from the store. `SettingsPagesJourneyTest` checks the preference store and every other file of the data folder | `UpdateChecker.token` (`TOKEN_KEY` in `SecretStore`), `AboutSettings.kt` (`TokenRow`) |
| The self-test (`zoukan selftest --network`, run by the install checks) takes a GitHub token only from the `ZOUKAN_SELFTEST_GITHUB_TOKEN` environment variable (CI's `GITHUB_TOKEN`), keeps it in memory for its one update check, and never writes it to the report, the log or the throwaway profile; the app itself never reads that variable | `SelfTest` (`TOKEN_ENV`, `MemorySecretStore`), `install-check-macos.sh`, `install-check-windows.ps1` |
| The release feed's address is fixed: no setting changes it. Tests swap the whole checker through `Updates.checkerForTests`, which is `internal` and set only by code; a checker pointed elsewhere still downloads only from `https://` addresses on its API host, and every installer still needs the release signature | `desktop/.../update/Updates.kt` (`checker`, `checkerForTests`) |
| The release key is fixed: no setting, system property or file changes it. Tests that download and install an update offline (`SettingsPagesJourneyTest`) put a key made for the test in its place through `Updates.releaseSignatureForTests`, which is `internal`, set only by code and `null` in the shipped app; the test checker keeps the `https://api.github.com` addresses and its OkHttp client alone carries them to the fixture server (`FixtureReleases.client`), so the https and API-host checks run unchanged. The download-and-check steps take the key as a parameter (`Updates.downloadVerified`), which `UpdatesDownloadTest` drives with a test key: a changed installer, another key's signature and a missing signature are refused and deleted | `desktop/.../update/Updates.kt` (`releaseSignature`, `releaseSignatureForTests`, `downloadVerified`) |

Residual risk:

1. **Whoever holds the release signing key can publish an update every new install accepts.**
   The key is the `ZOUKAN_RELEASE_SIGNING_KEY` repository secret; anyone who can read repository
   secrets (an account with admin access, or a change to the release job that reaches `master`)
   can sign what they like. Publishing a release without that key is not enough.
   Rotating the key needs one release signed with the old key (`desktop/RELEASING.md`).
2. **The check and the run are not one step.** The installer and its signature sit in the updates
   folder, which any same-user process can write to. The copy that is checked and started lives
   in a fresh folder, so swapping the downloaded file no longer changes what runs, but a same-user
   process can still write to that folder (on Windows it inherits the updates folder's access
   rules) and replace the copy between the check and `msiexec` or `open` reading it. A same-user
   process can already do everything the installer can.
3. Apps from before the release signature do not check it and still update on the digest alone.

## Web and embedded Chromium

The built-in browser (JCEF) solves Cloudflare challenges and hosts site and tracker sign-ins.
Extensions' hidden WebViews run on the same Chromium and share its profile,
`<cache>/cache/browser`, where `<cache>` is `%LOCALAPPDATA%\Zoukan-local` for the default Windows
data folder (outside the roaming profile; Chromium's natives sit beside it in `<cache>/browser`)
and the data folder itself otherwise (`ZoukanDirs`, `CacheFolders`). A browser window has a purpose: browsing a site (`BROWSE`), a Cloudflare
check the app met (`CHALLENGE`) or a tracker or E-Hentai sign-in (`SIGN_IN`).

Verified and trusted:

| Control | Where |
|---|---|
| Start address must be http/https with a host; `file:` and other schemes refused | `desktop/.../browser/BrowserWindow.kt` (start-URL check) |
| The top frame of a browser window or an extension's WebView goes only to http, https or `about:blank`: a navigation to `data:`, `javascript:`, `file:`, `blob:`, `chrome:` or an app scheme is cancelled and logged (scheme only). A tracker sign-in still hears of its app-scheme redirect first; an extension's WebView also loads the exact address the extension asked for, and its `shouldOverrideUrlLoading` still hears of a cancelled one, posted to the WebView's looper so an extension that blocks there cannot hold up Chromium, with its answer ignored | `BrowserWindow.requestHandler` (`onBeforeBrowse`); `android-compat/.../webkit/KcefPageRules.kt` (`KcefNavigationPolicy`), `KcefWebViewProvider` (`RequestHandler.onBeforeBrowse`), `KcefCancelledNavigation` |
| A page's alert, confirm and prompt: suppressed when they come from a frame of another origin than the top page (Chromium reports the origin, not the frame, so a same-origin iframe counts as the page), and a page past 3 in 10 seconds is silenced until the top frame moves on (a `history.pushState` to another address counts as moving on: accepted). A page with no web origin (`about:blank`, an extension's `loadDataWithBaseURL` with no base address) gets its own dialogs under the same limit, as on Android; a dialog with no web origin on an http or https page is another frame's and is suppressed. `onbeforeunload` never keeps a window or WebView from closing or leaving. A browser window shows Chromium's own dialog; an extension's WebView hands it to the extension's `WebChromeClient` and cancels it when the client does not handle it | `KcefPageRules.kt` (`JsDialogLimiter`); `BrowserWindow.jsDialogHandler`; `KcefWebViewProvider.JsDialogHandler`; `android-compat/.../android/webkit/JsResult.java`, `JsPromptResult.java` |
| Cookie sync of a browsing or challenge window is scoped: at open only the app's cookies for the start site (registrable domain) are copied into Chromium (cookies Chromium already keeps stay), and cookies are copied back only from pages on a trusted site: the start site, sites the user typed, clicked through to or opened from the pop-up bar, and sites a trusted page redirected to. A page a script sent the window to keeps its cookies in Chromium. Sign-in windows keep the unscoped sync (every cookie both ways), since a sign-in may pass through other sites' pages; extensions' WebViews also copy every cookie in at creation and copy back from every page they load, as before | `desktop/.../browser/BrowserCookieScope.kt`; `BrowserWindow` (`copyCookiesFrom`, `onBeforeBrowse`); `CefCookieSync.copyToCef(includeHost)` |
| No JavaScript-to-Java bridge exists; the only channels out of a page are the cookie sync and the main-frame URL intercept | absence by construction in `BrowserWindow` |
| Links opened in the system browser are http/https with a host only — `javascript:`, `file:` and custom schemes never reach the OS handler | `desktop/.../app/ExternalLinks.kt` (`webUri`) |
| Chromium natives downloaded only with the SHA-256 pinned for that exact JCEF build and platform (missing pin = no download), over https mirrors, installed atomically, half-installed state deleted. The download uses the app's DNS but none of its interceptors, cookies or cache, so the mirrors get OkHttp's own user agent and nothing of the sources | `desktop/.../browser/ChromiumNatives.kt` (`ensureInstalled`, `pinnedSha256`, `forDownload`, the `jcef-natives.sha256` pin file) |
| No page gets a native window: one would escape the app lock and the secure screen. In a browser window a pop-up to the same site (registrable domain) loads in place; one to another site is stopped and offered in a "Pop-up blocked" bar (Open loads it in place), or dropped without a word in a challenge window or when it goes to a blocked host. An extension's WebView loads a pop-up in place only when the extension let scripts open windows and the address is http/https, as Android's WebView with multiple windows off; anything else is dropped | `BrowserWindow` (`onBeforePopup`, `BrowserPopups.decide`, `PopupBar`); `android-compat/.../webkit/KcefContentRules.kt` (`KcefPopupPolicy`), `KcefWebViewProvider` (`onBeforePopup`) |
| Nothing from the web is saved as a file: every download Chromium starts, in a browser window or an extension's WebView, is cancelled | `KcefContentRules.kt` (`CefDownloadBlocker`), added in `BrowserWindow.open` and `KcefWebViewProvider` |
| Chromium's background services that contact Google are off (field trials, component and model downloads, Optimization Hints, Domain Reliability, hyperlink auditing, media router discovery, autofill form signatures, network time), as are Reporting and Network Error Logging. Profile preferences turn off Safe Browsing, history, network prediction (preconnects and prefetches), spellcheck (its dictionary download), password saving and autofill, and keep WebRTC off non-proxied UDP, so a page cannot learn the local or public address around a proxy. A preference this Chromium does not know is skipped with a warning in the log | `desktop/.../browser/JcefRuntime.kt` (`PRIVACY_SWITCHES`, `PRIVACY_PREFERENCES`, `applyPrivacyPreferences`) |
| The profile keeps as little of what was visited as Chromium allows: the HTTP and media caches are capped at about nothing (`--disk-cache-size=1`, `--media-cache-size=1`); History, Top Sites, Favicons, Visited Links, Network Action Predictor, Reporting and NEL, Shortcuts, `Cache`, `Code Cache` and `chrome_debug.log` are deleted before Chromium starts and again at quit (a file Chromium still holds, on Windows, goes at the next start); Chromium writes no log unless `-Dzoukan.cefDebug=true`. Links are never followed or deleted | `JcefRuntime` (`applySettings`, `cleanProfileAtQuit`), `desktop/.../browser/BrowserProfileCleanup.kt`, `Main.kt` |
| Extensions' WebViews and `http.agent` (sent by extensions and the JDK's `HttpURLConnection`) use the app's Chromium user agent for this OS, and a WebView running the default user agent sends client hints (`Sec-CH-UA`, `navigator.userAgentData`) that match it: desktop, this OS and architecture, the same Chromium version; on Windows the platform version is Chromium's (from the build number, read once with `%SystemRoot%\System32\reg.exe` with a timeout; without it, guessed from the OS name) | `ZoukanRuntime.useChromiumUserAgent`, `android-compat/.../webkit/KcefWebSettings.kt` (`userAgentProvider`, `desktopUserAgentMetadata`) |
| The content blocker cancels a page's requests for scripts, images and frames from hosts on its list, when they go to another site than the page in the top frame. It never blocks the page itself, a site's own hosts, `/cdn-cgi/` paths, Cloudflare's, hCaptcha's and reCAPTCHA's checks, Google's and several trackers' sign-in hosts, MangaDex or E-Hentai, and it is off in challenge and sign-in windows. It is on by default (Advanced > "Block ads and trackers"); browser windows use the built-in list (`blocklist.txt`, written for this project) and the user's lists, extensions' WebViews the built-in list only. It is a privacy feature, not a security boundary: every error lets the request through. In extensions' WebViews the filter runs in a callback from Chromium's native code, so even an `Error` (out of memory, a missing class) is not thrown back there: it is logged, the first one is written as a crash report (not fatal), and the request goes ahead | `desktop/.../browser/blocking/BlockPolicy.kt`, `ContentBlocker.kt`; `BrowserWindow.requestHandler`; `KcefContentRules.kt` (`KcefRequestFilter`), `DesktopAppModule` |
| Block lists the user adds ("Extra block lists") are untrusted input: https addresses only (at most 10), fetched with the plain client (no cookies, the app's DNS) without following a redirect between https and http, refused past 8 MiB, parsed as host names only (hosts files, `\|\|host^` rules and `@@\|\|host^` exceptions; anything else is ignored), capped at 200,000 hosts and exceptions a list and 400,000 in all, and stored with an atomic move in `<data>/blocklists`. An exception only ever lets requests through: in the built-in browser its host and subdomains are blocked by no list, the built-in one included. The lists are fetched only while the blocker is on: with it off, opening a browser window downloads nothing, and the first window after it is turned on downloads them when due. The weekly clock restarts only when at least one list downloads; when none does, the next try is after an hour, doubling up to a week (kept in memory). The addresses are kept in the encrypted preferences | `desktop/.../browser/blocking/UserBlockLists.kt`, `BlockListParser.kt`, `BlockRules.kt`, `RefreshBackoff.kt`, `ContentBlocker.download` |

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
3. **Third-party cookies are allowed** in the browser windows and extensions' WebViews (a
   maintainer decision; see Accepted risks). A tracker embedded on several sites can recognize
   the user across them. The content blocker removes the
   known ad and tracker hosts, which shrinks this but does not end it. Android's WebView blocks
   them by default (`desktop/PARITY.md`).
4. The content blocker matches whole hosts only: no paths, no cosmetic filtering, no first-party
   trackers, and every error lets the request through. A blocked site can move its ads to its own
   host. It is not on in challenge and sign-in windows.
5. Incognito mode does not change the browser: it uses the same profile, and what it keeps
   (cookies, local storage) stays after incognito ends.

## Network and privacy

Who learns what the user reads: name lookups, the proxy, caches and what the requests say about
the app.

Verified and trusted:

| Control | Where |
|---|---|
| DNS over HTTPS is on by default, with Quad9 (`dns.quad9.net`, the endpoint that blocks known-malicious domains). Only the default changes: a stored choice, off included, is kept, and the app never writes the preference itself. A restored backup's choice is applied only when the user confirms it in a Settings restore; data sync and the command line drop it | `core/common/.../network/NetworkPreferences.kt` (`defaultDohProvider`), `desktop/.../DesktopAppModule.kt`; `DesktopBackups.SENSITIVE_KEYS` |
| A public name that DNS over HTTPS cannot resolve fails; it is never sent to the system's DNS instead. Only local-only names (single-label names such as `localhost` or `nas`, and `.localhost`, `.local`, `.lan`, `.home`, `.home.arpa`, `.internal`, `.localdomain`, `.corp`, `.intranet`, `.private`, `.test`, `fritz.box`) and the host name of the proxy set in the OS go to the system's DNS, which alone can answer them. The Komga and Kavita trackers' fallback to the system's DNS covers the local-only names only. The DNS over HTTPS queries resolve their server's name to its fixed bootstrap addresses and run on their own threads, whose JDK lookups (a SOCKS proxy's name) use the system's DNS, so a query never waits on another query | `core/common/.../network/DohProviders.kt` (`isLocalOnlyHostName`, `LocalNamesSystemDns`, `desktopDohDns`, `isDohQueryThread`, `withSystemFallback`), `SystemProxyHosts.kt` (`isSystemProxyHost`), `DohPlatform.desktop.kt`, `NetworkPlatform.desktop.kt` (`SelectedProxyRecorder`) |
| The JDK's own lookups (`InetAddress`, so `URL.openConnection`, `ImageIO`, `java.net.http` and extension code that does not use OkHttp) go through the app's DNS once the runtime has started. The computer's own name, local-only names, the OS proxy's host name, a lookup made on a DNS over HTTPS query's thread and a lookup made inside one (the app's DNS reaching the system's) use the JDK's resolver. A failed lookup fails, with no fallback; reverse lookups fail while DNS over HTTPS is on, so an address is never sent to the system's DNS | `desktop/.../network/AppDnsResolver.kt` (`AppDnsResolverProvider`, registered in `META-INF/services`), `ZoukanRuntime` |
| Chromium resolves names itself, with the same provider in "secure" mode (no fallback), written to its `Local State` before it starts | `desktop/.../browser/ChromiumDns.kt` |
| The app's HTTP follows the proxy set in the OS (`-Djava.net.useSystemProxies=true` for the run task and the installers; not for test JVMs), as Chromium does by itself. No client in the app picks a proxy of its own (`Proxy.NO_PROXY`, `.proxy(...)`, a `ProxySelector`), which a source scan enforces | `desktop/build.gradle.kts` (`jvmFlags`), `desktop/packaging.gradle.kts`; `desktop/src/test/kotlin/zoukan/desktop/network/SystemProxySourceScanTest.kt` |
| No HTTP disk cache on desktop: OkHttp's would keep request addresses (search queries) and responses in plain text. The clients share a 5 MiB cache held only in memory (an in-memory file system that never writes to disk, bounded at three times the cache size, one response at most the cache size), gone when the app quits. The `network_cache` folder earlier versions left is deleted | `core/common/.../network/NetworkPlatform.desktop.kt` (`networkCache`, `MemoryNetworkCache`), `MemoryFileSystem.kt` |
| Images fetched by address (extension icons, tracker covers, description images from any host) send the cookies the app has but keep none a response sets, so an image host or ad server cannot leave a cookie in the store every source shares | `desktop/.../network/SendOnlyCookieJar.kt`, `Images.imageClient` |
| Requests that are not for a source (the update check, the Chromium download, user block lists) use the plain client, or for the Chromium download one built the same way: the app's DNS, but no source cookies, no cache, no source user agent | `NetworkHelper.plainClient`, `ChromiumNatives.forDownload` |

Residual risk:

1. **The DNS over HTTPS provider sees every name the app looks up**, Quad9 by default, in place
   of the system's resolver. Its filtering can block a site it lists as malicious. Lookups made
   before the runtime starts use the system's DNS.
2. **A proxy sees every host name.** With an HTTP proxy (CONNECT) or SOCKS5 the proxy resolves the
   names, so DNS over HTTPS resolves only the proxy's own name, and the DNS over HTTPS queries go
   through the proxy too. A proxy that asks for a password is not supported (requests fail with
   407), and a PAC or WPAD setup works only as far as the JDK reads it. A proxy given by host name
   is resolved with the system's DNS, which so learns that name (and only that name); the app
   reads the proxy's name from the OS settings (`isSystemProxyHost`, for http, https and SOCKS
   probe addresses) and remembers each proxy OkHttp picks for the app's clients before it looks
   the proxy's name up (`SelectedProxyRecorder`, at most 32 names for the run), so a PAC file
   that picks a different proxy per site works for them too. A per-site proxy first used by code
   that does not use those clients (the JDK's own connections, a client an extension builds from
   scratch) can still have its name sent to DNS over HTTPS. A compiled extension can still set a
   proxy of its own for its client; the source scan covers the app's code only.
3. **The app can be told apart from a browser.** The JDK's `HttpURLConnection` adds ` Java/21` to
   `http.agent`, and OkHttp's TLS and HTTP/2 handshakes are the JVM's, not Chromium's, whatever
   the user agent says. Extensions read Komikku's version from the package manager, as on Android.
4. **The app contacts servers without being asked**: the extension stores' lists at each start,
   GitHub's release feed 30 seconds after start and then daily (on by default; About > "Check for
   updates automatically"), the sources of a saved download queue, which carries on after a
   restart, and each user block list's host weekly while "Block ads and trackers" is on (when a browser window opens), which learns the user's address. Chromium starts only when a page needs it. Discord Rich Presence is
   off by default.

## Local files

Archives, backups and XML the app reads; names it writes.

Verified and trusted:

| Control | Where |
|---|---|
| Download filenames sanitized as Android builds them: control chars, `/`, `\`, `:`, drive-colon parts removed | `desktop/.../download/FileNames.kt` (`buildValidFilename`) |
| CBZ entries are generated page names only, on write and on read; a zip entry name never becomes a path | `desktop/.../download/Cbz.kt`, `ChapterDownloader.kt` |
| ComicInfo.xml parsing refuses doctype declarations, so no XXE | `desktop/.../download/ComicInfoXml.kt` |
| EPUB XML allows a doctype (XHTML carries one) but fetches nothing outside the file: secure processing on, external general and parameter entities off, external DTDs and schemas not loaded, no XInclude, entity references not expanded | `desktop/.../local/LocalFormat.kt` (`xml`) |
| Archive entry names can't escape the unpack folder: absolute paths, `..`, and drive-colon parts refused | `desktop/.../local/Archives.kt` (`entryPath`, `unpackEntry`) |
| Unpacking is capped (per entry, per archive total, per entry count) and symlink entries are skipped, in every path: the system tar writes nothing to disk but streams the entries to the app as a TAR (`tar -cf - @archive`), which goes through the same budget, and it is killed when over budget or after five minutes | `Archives.kt` (`Budget`, `Limits`, `systemTar`) (hardened) |
| RAR/7z/TAR read in-process by junrar and Commons Compress; anything they cannot read falls back to the system `tar` with a five-minute timeout | `Archives.kt` (`unpack`) |
| Backup restore refuses a file that is not a backup before it is applied | `desktop/.../backup/RestoreGate.kt` |

Residual risk:

1. The RAR 5 and odd-7z fallback hands the archive to the system `tar` (bsdtar/libarchive),
   which has CVE history in RAR5 and 7z parsing; it runs as the user on hostile data. Java-side,
   junrar and Commons Compress parse the same hostile data in-process. The caps and symlink-skip
   bound the blast radius; they do not remove memory-safety exposure in those parsers.
2. A crafted archive can still cost CPU and disk up to the caps (a budget of gigabytes), and a
   same-user process can tamper with anything in the cache folders. Memory is bounded per page:
   a page or cover read into memory has its own, much smaller cap (`Limits.pageBytes`, 64 MiB),
   and a larger one fails with a clear message instead of filling the heap; while unpacking, an
   entry is held in memory up to that cap, one at a time. Without the file key an open chapter's
   pages are all kept in memory, up to an eighth of the heap in all (`Limits.memoryBytes`); a
   larger chapter is refused rather than risk running out of memory. A cover holds one page.

## Secrets at rest

The database, preferences and the app's cookie store are encrypted, as are covers, the image
cache and the chapter cache, and the HTTP cache is kept in memory only. What still touches the disk
unencrypted is deliberately small. The accepted exceptions are automatic and safety backups
(residual risk 4), logs and crash reports (5), the built-in browser's profile (6) and a page set
as a cover, for a moment (7). On macOS the logs, crash reports and the disposable caches are kept
out of Spotlight and Time Machine (5), and by default so are the storage folder's downloads,
local source and automatic backups (two settings in Settings > Data and storage).

Verified and trusted:

| Control | Where |
|---|---|
| Database always encrypted (SQLCipher 4 scheme, SQLite3MultipleCiphers) under a random 256-bit raw key | `desktop/.../db/DesktopDatabase.kt` (`createDriver`, `properties`); every connection, the database thread's kept one included, opens with those properties in `desktop/.../db/DesktopSqliteDriver.kt` |
| The database key lives in the OS secret store, never next to the file; an existing database with a missing key refuses to open (fail-closed), as does an empty database file whose `-wal` still has data in it (a stale `-shm` alone, or an empty `-wal`, holds no data and does not block start-up); a secret store error stops start-up before any key is made | `DesktopDatabase.databaseKey`, `KeyMissingException`, `JournalWithoutKeyException` |
| The copy made before a schema migration (`zoukan.db.pre-v<N>`, with its `-wal` if any, beside the database) is a byte copy, so it is encrypted with the same key; only the latest is kept. A database from a newer build is refused before anything writes to it: its version is read through a read-only connection, so not even a WAL checkpoint touches the file or its `-wal` | `DesktopDatabase.copyBeforeMigration`, `TooNewException` |
| Preferences, cookies and session files encrypted at rest with AES-256-GCM under a second random OS-store key | `desktop/.../secrets/FileCipher.kt` |
| Windows: Credential Manager generic credentials, per-user, non-roaming; macOS: login Keychain generic passwords | `desktop/.../secrets/WindowsCredentialStore.kt`, `MacKeychainStore.kt` |
| The app's cookie store (`java.net.CookieManager`, what OkHttp and the sources use) writes only cookies with an expiry, to `cookies.json`, encrypted with `FileCipher`; session cookies stay in memory. It is synced with Chromium's cookies while a browser window is open. Chromium does not persist session cookies either (`persist_session_cookies = false`) | `desktop/.../network/PersistentCookieStore.kt`, `android-compat/.../webkit/CefCookieSync.kt`, `desktop/.../browser/JcefRuntime.kt` (`applySettings`) |
| Covers and cached images encrypted at rest with the same file key (AES-256-GCM, written through a temporary file and moved into place): the source covers in `cache/covers`, custom covers in `cache/covers/custom`, and Coil's disk cache of images fetched by address (`<cache>/cache/images`, its journal included). Each file is encrypted whole, except Coil's journal, which is framed: each appended chunk is its own AES-GCM frame with a length prefix, so a flush appends a frame instead of rewriting the file, and a torn last frame after a crash is dropped while the frames before it are kept. Every encryption, frame or whole file, takes a fresh random 96-bit nonce from `SecureRandom`, as `FileCipher` does for every file, so no nonce repeats under the key in practice. Without the file key nothing is cached on disk and a custom cover cannot be saved. While incognito is on (for the app, or for a cover's source), no new source cover or Coil image is written to disk; what is cached is still read. Covers saved before encryption are rewritten encrypted when first read, and a plain image cache is deleted; a custom cover is never deleted, even when it cannot be decrypted. Temporary files that writes cut short left behind are deleted at start, matched by the writer's own name pattern only | `desktop/.../secrets/EncryptedFiles.kt`, `EncryptedFileSystem.kt`; `desktop/.../ui/common/Images.kt` (`coverBytes`, `cachedCover`, `encryptedDiskCache`, `IncognitoDiskCacheInterceptor`, `init`), `desktop/.../manga/CustomCovers.kt` (`deleteTemporaryFiles`) |
| The chapter cache (`<cache>/cache/archives`, the unpacked RAR, 7z and TAR chapters) encrypted at rest with the same file key: each page and ComicInfo.xml is unpacked into memory and written encrypted, whichever reader unpacks it (the system tar included), so no page is plain on disk; a page is decrypted in memory when read. A chapter is reused only when its marker decrypts with the current key, otherwise it is unpacked again. Without the file key nothing is cached and the open chapter's pages stay in memory, at most an eighth of the heap in all (`Limits.memoryBytes`); a larger chapter is refused with a message. A cover read from an archive holds one page in memory at a time and writes nothing to the cache, with or without the key. At start the cache is cleared of what is not a whole encrypted chapter (the plain cache from before encryption, folders a crash cut short); only the cache's own folder, never downloads or the storage folder | `desktop/.../local/Archives.kt` (`open`, `prepareCache`, `FolderSink`, `readCachedPage`), `ZoukanRuntime.kt` |
| FFmpeg decodes AVIF and HEIF pages and covers from memory (a custom `AVIOContext`), never from a temporary file. ImageIO reads and writes pages and covers held in memory through streams in memory (over the byte array itself, `byteArrayImageInputStream`, and into one, `ByteArrayImageOutputStream`; elsewhere `MemoryCacheImageInputStream` / `MemoryCacheImageOutputStream`), never through its default file cache, which copies the image to a temporary file in the system's temporary folder; a source scan refuses the file-cached calls in the app's own code, and every command turns ImageIO's file cache off at start for any other caller (extensions' image code through the Android layer included). A page set as a cover goes through a temporary folder of the app's own (`<cache>/cache/tmp`, beside the chapter cache; `cache/tmp.noindex` on macOS), deleted right after and emptied at every start, not the system's temporary folder | `desktop/.../image/FfmpegImageDecoder.kt` (`MemoryInput`), `desktop/.../AppTempFiles.kt`, `ReaderScreenModel.kt` (`setPageAsCover`), `desktop/.../image/MemoryImageIo.kt`, `Main.kt` (`runCommand`), `ImageIoCacheScanTest` |
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
4. **Automatic backups are not encrypted.** Every 12 hours by default (Settings > Data, "Automatic
   backup frequency"; off when set to never), `AutoBackup` writes a gzipped `.tachibk` (the Android
   app's protobuf format) to `<storage>/autobackup` and keeps the newest four that Zoukan wrote.
   A backup holds the library, chapters and read state, history, tracker bindings, categories and
   app settings. Private settings (tracker tokens, logins) are left out unless the user ticks
   "Include sensitive settings" in a manual backup (`BackupOptions().privateSettings` is off by
   default). Accepted because the Android app must be able to read these files, and they are the
   way back when the OS secret store loses the database key. Anyone who can read the storage
   folder (a cloud drive, a NAS, another user with access) can read the library and reading
   history that the database encryption protects. Users who keep storage on a shared folder can
   switch automatic backups off. Backups Zoukan writes from now on (automatic, manual and safety
   backups) are created readable by their owner only (`0600`) where the file system has POSIX
   permissions; existing files, including the Android app's, keep their permissions. On macOS,
   Settings > Data and storage warns (without blocking) when the storage folder is in iCloud Drive
   or a `~/Library/CloudStorage` folder, or in the folders older Dropbox, OneDrive and Google Drive
   apps keep in the home folder, or in Desktop or Documents, which iCloud may sync.
   Code: `desktop/.../backup/AutoBackup.kt`, `DesktopBackups.create`.
   **Safety backups** (written before a restore, a data sync that changes the library, Clear
   database, a migration and Clear manga's chapter removal) are the same unencrypted format with
   the same default choices (no private settings), but go to `backups/safety` inside the app's
   data folder, never the storage folder, so they are not on a shared or cloud folder; the newest
   five of each kind are kept. Anyone who can read the data folder can read them, as with the
   logs (residual risk 5). Code: `desktop/.../backup/SafetySnapshot.kt`.
5. **Logs and crash reports are plain text.** `logs/` holds the rolling log (4 files of 5 MB) and
   `crashes/` holds crash and error reports (the stack trace and the end of the log) and copies of
   JVM crash files (without environment variable values). Every line of the log, and every report,
   has sign-in codes, tokens, passwords, API keys and `Bearer` values in `name=value` form redacted
   (`CrashReporter.redact`, applied by the log's layout); cookies, other header values and tokens
   in a URL's path are not recognised. A failed page load in the built-in browser logs only the
   host. A JVM crash file (`hs_err_pid<n>.log`) in the temp or working folder is copied and its
   original, which holds environment variable values, deleted only when it is Zoukan's: its
   command line names Zoukan's main class, or its process id (file name and header) is that of a
   Zoukan run that left its `.running-<pid>` marker and it has a command line that names no other
   program (empty, `<unknown>`, or the installed launcher, bare or at its installed path, as the
   first word). A file with no command line in its first 64 KiB, or one that starts with options,
   is left alone, as is another program's file with a reused process id; if the copy fails the original is kept. Titles of what was read, URLs and file paths can appear in the log and in crash reports;
   the crash dialog says so. Anyone who can read the data folder can read them.

   **Incognito.** While app-wide incognito mode is on, nothing is logged at all (not to the log
   file and not to the console). A crash or error report written then keeps only the exception
   class names and stack frames, for the exception and all of its causes and suppressed
   exceptions; it leaves out every exception message, because messages often carry a chapter
   address or title, and the end of the log, and says so. The crash dialog likewise names only
   the exception class. Stack frames still name the code that failed, for example an extension's
   classes. A JVM crash file is copied at the next start with environment values removed and
   secrets redacted but otherwise unchanged, so it can hold memory fragments from the incognito
   session. Lines logged at start before the preferences are open (the version, platform and data
   folder) are still written, and a few lines can be written in the moment after incognito is
   turned on, until the change reaches the log filter. If the incognito preference cannot be read,
   logging stays on, so a broken start can still be diagnosed. Per-extension incognito does not
   stop logging. Chromium's own log is outside this rule. Crash and error reports, and Zoukan's
   reports in the reports folder, are deleted after 30 days. Code: `desktop/.../app/IncognitoLog.kt`,
   `CrashReporter.kt`.

   **Data folder.** On POSIX systems the data folder is made `0700` at every start when it is
   Zoukan's own per-platform folder, or when Zoukan created it on that start; a folder chosen with
   the data folder override (`zoukan.dataDir` or `ZOUKAN_DATA_DIR`) that already existed keeps the
   permissions its owner gave it, and a data folder that is a symbolic link is not changed. On
   macOS the log, crash and reports folders, the caches (Coil's image cache, the chapter cache,
   FFmpeg's natives and the Android layer's cache folder) and the app's temporary folder take a
   `.noindex` name (`logs.noindex`, `crashes.noindex`, `reports.noindex`, `cache/images.noindex`,
   `cache/archives.noindex`, `cache/tmp.noindex`, `cache/javacpp.noindex`,
   `android/android-compat/appdata/cache.noindex`), so Spotlight does not index them, and carry
   Time Machine's exclusion (as `tmutil addexclusion` sets it), as does `updates/`, which is
   created at start so the first downloaded installer is already excluded. One of these folders
   that is a symbolic link is skipped (noted in the log), so the folder it points to is never
   created or excluded, and every extended-attribute call uses `XATTR_NOFOLLOW`, so it acts on
   the path itself and never on a link's target. A profile with the old
   names has them renamed at start; if a rename fails, the old folder is used for that run. Code:
   `desktop/.../DataFolderPrivacy.kt`, `ZoukanDirs`.

   **Storage folder (macOS).** By default the storage folder's `downloads`, `local` and
   `autobackup` folders are added to Spotlight's Search Privacy list and carry Time Machine's
   exclusion, with a setting for each in Settings > Data and storage. Only those three folders are
   marked, never the chosen folder itself: each must be a real directory directly in the storage
   folder with exactly that name, case included, after links are resolved. A folder reached
   through a link, or one whose name differs only in case (such as `~/Downloads` when the home
   folder is the storage folder), is never marked or created. Zoukan records the canonical path of
   every folder it marks, in app-state preferences that are never in backups. When a setting is
   turned off or the storage folder changes, it removes marks only from those recorded paths, so
   exclusions the user made outside Zoukan stay. A folder that already carries the exclusion when
   Zoukan first sees it is treated as the user's: it is not recorded and stays excluded after the
   setting is turned off (when Zoukan cannot read whether it does, it leaves the folder unmarked and
   tries again at the next refresh). A recorded folder that cannot be reached, or whose removal
   fails, keeps its record and is retried later. A storage folder that is unavailable is
   marked at the next refresh (a settings or storage change, or the next start), not when it comes
   back. When the last attempt to apply a setting failed, Settings shows an error line under its
   switch, so a user in incognito mode, where nothing is logged, still learns of it.

   The folders keep the Android app's names, and a `.metadata_never_index` file, a hidden flag or a
   `.noindex` child do not stop current macOS from indexing a folder, so Spotlight's list is
   changed through the call System Settings uses (`_MDSetExclusion`, not public API). That call is
   made only in a short-lived helper process (`SpotlightExclusionHelper`), started only when an
   entry is to be added or removed, with Zoukan's own bundled Java runtime (`bin/java`), a 32 MB
   heap, headless, a class path of only Zoukan's, JNA's and Kotlin's jars, and Java option and
   agent variables removed from its environment. It opens no data folder, log, preference, lock or
   secret store; JNA may unpack its native library into its own cache folder
   (`~/Library/Caches/JNA/temp`) and deletes it once loaded. The helper's JVM writes no core dump,
   performance-data file or crash report (`-XX:-CreateCoredumpOnCrash`, `-XX:-UsePerfData`,
   `-XX:ErrorFile=/dev/null`); if a crash report or compiler replay file is written to its working
   folder anyway, Zoukan deletes that helper's files after it exits, by process id, regular files
   only. The helper ends itself after 12 seconds, Zoukan stops it after 15 and kills a running
   helper when it quits. A crash, a non-zero exit, a timeout or output that does not parse is
   logged as a warning and never reaches Zoukan's process, and the helper is then not started
   again until Zoukan restarts. The Time Machine attribute is set in Zoukan's process (public
   `setxattr`/`removexattr`). Search Privacy entries are by path and stay after Zoukan is deleted.
   Code: `desktop/.../storage/StorageFolderPrivacy.kt`, `SpotlightExclusion.kt`,
   `SpotlightExclusionHelper.kt`, `SpotlightNative.kt`.
6. **The built-in browser's profile is not encrypted.** `<cache>/cache/browser` is shared by the
   browser windows, the challenge windows and extensions' WebViews, and sits outside `FileCipher`.
   After the clean-up at start and at quit (Web and embedded Chromium) it still holds what sites
   need: their cookies, Local Storage and IndexedDB, Local State and Chromium's other profile
   files. The `Cookies` database, with the expiring cookies the app copies into it
   (`CefCookieSync`), is protected only by Chromium's `os_crypt` (DPAPI on Windows; on macOS
   probably a fixed mock-keychain key); Local Storage and IndexedDB are plain. Whoever copies the
   profile's folder may be able to read those sign-ins and which sites stored data. A file Chromium
   still holds at quit stays until the next start. On Windows the profile is in `%LOCALAPPDATA%`,
   so a roaming setup does not copy it; an older version's profile in the roaming data folder is
   moved there at start (renamed, or copied and then deleted), and stays in the roaming folder for
   a run in which it cannot move. When an older version was run again after the move and both
   folders exist, the one used more recently is kept and the other deleted. With a chosen data
   folder (`zoukan.dataDir`) the profile stays inside that folder and goes wherever it is copied.
7. **Some files stay plain.** A page set as a cover goes from memory straight to where the cover
   is kept, never through a temporary file: encrypted with the file key for a library entry, and
   for a local entry the user's own plain file in the storage folder, or `cover.cbi`, encrypted
   with the download password, when the entry has one or its chapters are encrypted. Covers are decrypted in memory, where Coil's memory cache
   (256 MiB) keeps them; a local source's covers are the user's own files in the storage folder,
   read as they are. The exception is `cover.cbi`, the cover of an entry whose chapters are
   encrypted (Komikku writes it): it is decrypted in memory with the download password and nothing
   decrypted is written to disk (the password is read from the secret store once a session for
   these covers and kept in memory until it is changed in settings or the app locks, so a grid asks the store once; a read that found no password, the store failing included, is tried again after a minute, so a denied OS prompt can come back about once a minute while encrypted covers load). Each time the app lock engages, that password is wiped and Coil's memory cache is emptied, so no decrypted cover or page preview stays in memory behind the lock; they are read again after the unlock (`Images.followAppLock`), and a cover taken from an encrypted chapter is written as
   `cover.cbi`, never plain. Without the password it shows as a broken cover. The encrypted
   caches' file sizes and counts still show how much is cached.
8. **Encrypted cache files are not bound to their place.** The frames of Coil's journal are not
   bound to their order, so a same-user process can reorder or drop whole frames without it being
   noticed, and no ciphertext is bound to its file's name, so a frame, or an encrypted cover or
   page, can be moved into another file under the same key. This touches the caches only: the
   effect is a wrong cached image or a wrong journal state, never the key. Binding the file name
   and the frame's index as associated data is a later change.

## Processes

Everything the app executes outside its own JVM.

Verified and trusted:

| Control | Where |
|---|---|
| Every external process is launched as an argv array through `ProcessBuilder` — no shell string is ever built, so no manga title, folder name or URL can inject a command | `InstallerSignature.kt` (PowerShell, with file paths passed through the environment, not the command line), `Updates.kt` (`msiexec`, `open`), `Archives.kt` (`tar`), `PowerStatus.kt` (`pmset`), `ReaderEffects.kt` (`caffeinate`), `ReducedMotion.kt`, `SpotlightExclusion.kt` (Zoukan's own `bin/java` running `SpotlightExclusionHelper`, folder paths as separate arguments) |
| URL opening goes through `java.awt.Desktop.browse`, never a shell | `ExternalLinks.browse` |

No shell out means the classic injection class — a chapter titled `foo"; rm -rf ~; "` — has no
sink. Residual risk is the usual one: these processes run with the user's privileges, on
arguments the app controls but a hostile archive's *presence* on disk can influence (the tar
fallback's input file is user- or source-chosen content, parsed by libarchive as above).

## Accepted risks

| Risk | Why accepted | What would change it |
|---|---|---|
| Extensions run in-process with full user privileges | The Android extension model, kept on purpose; the whole extension ecosystem depends on it, and Suwayomi-Server makes the same trade | Run extensions out-of-process with an IPC surface; a large, ongoing engineering effort |
| Store index self-declares its signing key over TLS | Same design as Mihon/Komikku; a curated or pinned root would break user-added stores, which the feature exists to allow | Signed indexes or an app-shipped list of known store keys, with user-added stores still allowed but flagged |
| dex2jar/ASM parse hostile APKs in-process before the trust decision | Only stores without jars reach it; the dominant store publishes jars; dex2jar is unmaintained, so "fix the parser" is not available | Drop the APK path (jars-only stores), or convert in a throwaway subprocess/sandbox with a download size cap |
| System tar (libarchive) reads RAR 5 and odd 7z archives | RAR 5 has no maintained pure-Java reader; without tar those chapters cannot open at all | The caps and symlink-skip shrink it (hardened); a pure-Java RAR 5 reader would remove it |
| OAuth login CSRF from unbound `komikku://` redirects | The trackers' app registrations only accept the custom scheme; Android inherits the same flaw | Trackers move to loopback or https redirects, then the window can bind the redirect to the page it was opened from |
| Third-party cookies allowed in the built-in browser and extensions' WebViews | A maintainer decision: pages, checks and sign-ins work as they do in an ordinary browser. The content blocker removes the known ad and tracker hosts. Android's WebView blocks them, so this is a parity difference (`desktop/PARITY.md`) | Block third-party cookies in Chromium's profile, or partition them per site |
| The built-in browser's profile (cookies, local storage) is not encrypted by the app | Chromium writes its profile itself, outside `FileCipher`, and sign-ins and Cloudflare clearances should survive a restart; history and caches are deleted, so what is left is what sites need | An in-memory profile (sign-ins then last one run), or one inside an encrypted container |
| Same-user malware reads secret-store keys | OS design; no desktop API grants per-read prompts or hardware binding for generic credentials | OS-level change; the app can only shrink what is at rest, which it already does |
| The updater sends its GitHub token to the API host named in the release JSON's asset address | Private-repo support needs it. Only `https://` addresses on the API host get a download request (and so the token); the asset's own redirect to storage on another host drops it. The installer must also pass the release signature, so a tampered release JSON cannot supply one | Fetch assets through a host the token is not sent to, if a private repository ever serves them from elsewhere |
| Build dependencies have no project-wide `verification-metadata.xml` | Upstream has none, and a partial file fails the build; Maven Central, Google and the Gradle plugin portal serve fixed versions over TLS. The one artifact from a plain GitHub repository, Suwayomi's `android-jar`, is pinned to a commit (`settings.gradle.kts`) and checked against a SHA-256 before anything compiles against it (`android-compat`'s `verifyAndroidJar`) | A complete `gradle/verification-metadata.xml` for every dependency, kept current on each update |
| App-lock verifies fail-open with no rate limit | The lock is a shoulder-surfer control, not a decryption boundary; PBKDF2 at 210k iterations bounds offline guessing | Make `verify` fail closed when the lock is enabled, and add a delay between attempts |
