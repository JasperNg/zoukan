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
| A failed background save stays pending, is retried, and is written at shutdown. A change is no longer lost when the settings file is briefly locked (antivirus, indexer, sync client). | `JavaSharedPreferences.kt` |
| An unreadable settings file is kept aside once (`<name>.xml.unreadable`, later ones with a timestamp) and never saved over: the first copy is never overwritten, and an encrypted file opened before the key is set is left untouched. | `JavaSharedPreferences.kt` |
| A stale temporary settings file is removed and never replaces cleared settings: clearing a settings file also removes its `.tmp`, and a `.tmp` beside a main file is deleted when the file is opened. The main file is not rewritten. | `JavaSharedPreferences.kt` |
| Editor and typed getters follow Android: the editor is emptied after a commit or apply, `clear()` runs before the puts of the same edit, `contains` and the `clear()` notification ignore a Set's internal entries, and a value read as another type than it was stored as throws `ClassCastException` (values written before types were kept are still read leniently). | `JavaSharedPreferences.kt` |
| A non-persistent multi-select keeps its values in memory and reads them back (`getValues` and `setValues` check `shouldPersist()`, as `ListPreference` does). | `MultiSelectListPreference.java` |
| The old default-preferences file is kept as `<name>.xml.migrated` (later ones `.migrated-<time>`) after its values move over, instead of being deleted. | `PreferenceManager.kt` |
| A switch or checkbox shows `summaryOn` or `summaryOff` for its state before the plain summary, as on Android (a summary provider still wins). | `TwoStatePreference.java` |

`AndroidCompatInitializer` and `AndroidCompat.startApp` are called by `zoukan.desktop.ZoukanRuntime`, after it registers the Injekt modules.

The Noto fonts in Suwayomi's resources are not copied (37 MB); `Typeface` falls back to the JVM's
fonts.

The vendored files keep Suwayomi's formatting and are not formatted by Spotless, so a later sync
with Suwayomi stays a clean diff.

## Updating

Copy `AndroidCompat/src/main/java` and `AndroidCompat/Config/src/main/java` from the new
Suwayomi commit over `src/main/java`, then reapply the `ZOUKAN` changes listed above
(`git diff` against the previous vendored commit shows them).
