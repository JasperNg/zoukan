# android-compat

The Android compatibility layer of Zoukan, the desktop app. Extensions are Android libraries: they
call `Context`, `SharedPreferences`, `WebView`, `Bitmap`, `Handler`, QuickJS and other Android APIs.
This module implements those APIs on the JVM.

It is [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server)'s `AndroidCompat` and
`AndroidCompat/Config` modules, vendored at commit `d5215280158b9b78d2e3ca46312e39dccde6b97b`
(7 Nov 2026), plus two files from Suwayomi's server (`BytecodeEditor.kt` and
`ChildFirstURLClassLoader.kt` in `suwayomi/tachidesk/manga/impl/util/`).

## License

The code from Suwayomi is licensed under the Mozilla Public License 2.0 (see `LICENSE`), which is
file-level copyleft: these files stay under the MPL-2.0, and changes to them are published with
them. Files Zoukan added (marked with `ZOUKAN`) are under the same license as the files they extend.
The rest of Zoukan stays under Apache-2.0.

## Changes from Suwayomi

Every change is marked with `// ZOUKAN -->` / `// ZOUKAN <--` (or `/* ZOUKAN --> */` inline).

| Change | Files |
|---|---|
| Koin replaced with Injekt, which Komikku and the extensions use. `KoinGlobalHelper` keeps its name but resolves from Injekt; the Koin modules are now Injekt modules. | `AndroidCompat.kt`, `AndroidCompatModule.kt`, `ConfigManagerModule.kt`, `CustomContext.java`, `PackageController.kt`, `KoinGlobalHelper.kt`, `KcefWebViewProvider.kt` |
| WebView runs on upstream JCEF ([jcefmaven](https://github.com/jcefmaven/jcefmaven)), whose Chromium builds are on Maven Central, instead of JetBrains' JCEF. Upstream JCEF has no `CefRendering`, so `CefBrowserHeadless` is the windowless browser that ignores painting; it has no media permission handler and no `CefApp.onInitialization`. | `KcefWebViewProvider.kt`, `CefHelper.kt`, `org/cef/browser/CefBrowserHeadless.java` (new) |
| Cookies a page sets in Chromium are copied to the app's cookie store after each page load, so OkHttp sees them (for example Cloudflare's `cf_clearance`). | `KcefWebViewProvider.kt`, `CefCookieSync.kt` (new) |
| `SharedPreferences.getAll()` returns each value with its type (Int, Long, Float, Boolean, String, Set) instead of all as text, and a Set as one value. The type is kept beside each value under `__zoukan_type__.<key>`. Backups need it: they store values by type, and a restore only writes over a value of the same type. | `JavaSharedPreferences.kt` |

`AndroidCompatInitializer` and `AndroidCompat.startApp` are called by `zoukan.desktop.ZoukanRuntime`, after it registers the Injekt modules.

The Noto fonts in Suwayomi's resources are not copied (37 MB); `Typeface` falls back to the JVM's
fonts.

The vendored files keep Suwayomi's formatting and are not formatted by Spotless, so a later sync
with Suwayomi stays a clean diff.

## Updating

Copy `AndroidCompat/src/main/java` and `AndroidCompat/Config/src/main/java` from the new
Suwayomi commit over `src/main/java`, then reapply the `ZOUKAN` changes listed above
(`git diff` against the previous vendored commit shows them).
