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

Vendored from Suwayomi-Server commit `d5215280158b9b78d2e3ca46312e39dccde6b97b`; that commit's
files are the upstream side of every diff. (Zoukan's own history was rewritten, and its first
commit of these files already holds the changes, so an earlier Zoukan commit is not a baseline.)

Every Zoukan edit sits inside one marker convention. A test (`VendoredFilesMarkedTest`) fails when
a file listed below loses its markers, when a block is not closed or is nested, and when a one-line
form is used:

- An edit is wrapped in `// ZOUKAN -->` and `// ZOUKAN <--` on their own lines (Java and Kotlin), or
  in `/* ZOUKAN --> */` and `/* ZOUKAN <-- */` on one line. Explain the edit in a normal comment
  inside the block.
- A file Zoukan added or rewrote starts with `// ZOUKAN: whole file -->` and ends with
  `// ZOUKAN: whole file <--`.
- Blocks never nest, and every opener has a closer. There are no one-line forms (`// ZOUKAN: ...`,
  a bare `// ZOUKAN`, a trailing `// ZOUKAN`, `/* ZOUKAN */`).
- Every file with a marker has a row in the table below, with its path under `src/main/java`.

Paths below are relative to `src/main/java`.

| Change | Files |
|---|---|
| Koin replaced with Injekt, which Komikku and the extensions use. `KoinGlobalHelper` keeps its name but resolves from Injekt; the Koin modules are now Injekt modules. | `xyz/nulldev/androidcompat/AndroidCompat.kt`, `xyz/nulldev/androidcompat/AndroidCompatModule.kt`, `xyz/nulldev/ts/config/ConfigManagerModule.kt`, `xyz/nulldev/androidcompat/androidimpl/CustomContext.java`, `xyz/nulldev/androidcompat/pm/PackageController.kt`, `xyz/nulldev/androidcompat/util/KoinGlobalHelper.kt`, `xyz/nulldev/androidcompat/webkit/KcefWebViewProvider.kt` |
| WebView runs on upstream JCEF ([jcefmaven](https://github.com/jcefmaven/jcefmaven)), whose Chromium builds are on Maven Central, instead of JetBrains' JCEF. Upstream JCEF has no `CefRendering`, so `CefBrowserHeadless` is the windowless browser that ignores painting; it has no media permission handler and no `CefApp.onInitialization`. JavaScript-interface calls arrive in chunks, and a destroyed browser is disposed after queued work. WebViewClient callbacks follow Android: `onPageStarted`, `onPageFinished`, `onReceivedError` and the cookie copy run for the main frame only, `onLoadResource` gets the resource's URL, and `shouldOverrideUrlLoading` is asked about navigations only. | `xyz/nulldev/androidcompat/webkit/KcefWebViewProvider.kt`, `xyz/nulldev/androidcompat/webkit/CefHelper.kt`, `org/cef/browser/CefBrowserHeadless.java` (new), `android/webkit/WebView.java`, `android/webkit/ConsoleMessage.java` |
| Cookies a page sets in Chromium are copied to the app's cookie store after each page load, so OkHttp sees them (for example Cloudflare's `cf_clearance`). `getCookie` matches paths as Chromium does and `removeAllCookies` clears Chromium's too. | `xyz/nulldev/androidcompat/webkit/KcefWebViewProvider.kt`, `xyz/nulldev/androidcompat/webkit/CefCookieSync.kt` (new), `xyz/nulldev/androidcompat/webkit/CookieManagerImpl.kt` |
| `SharedPreferences.getAll()` returns each value with its type (Int, Long, Float, Boolean, String, Set) instead of all as text, and a Set as one value. The type is kept beside each value under `__zoukan_type__.<key>`. Backups need it: they store values by type, and a restore only writes over a value of the same type. Saves are ordered, listeners run outside the lock, and the file codec and the test hook are Zoukan's. | `xyz/nulldev/androidcompat/io/sharedprefs/JavaSharedPreferences.kt`, `xyz/nulldev/androidcompat/io/sharedprefs/SettingsFileCodec.kt`, `xyz/nulldev/androidcompat/io/sharedprefs/ForTestsOnly.kt`, `android/preference/PreferenceManager.kt` |
| Android identity: `Build` reports an Android 14 phone (rewritten), and `CustomContext` reports the emulated app's package name, `getBasePackageName` and `getOpPackageName`. | `android/os/Build.java` (rewritten), `xyz/nulldev/androidcompat/androidimpl/CustomContext.java` |
| An extension's own `new OkHttpClient()` becomes a client whose names resolve through the app's DNS (over HTTPS when the user turned it on); `BytecodeEditor` rewrites the calls and descriptor names. | `xyz/nulldev/androidcompat/network/ExtensionHttp.java` (new), `suwayomi/tachidesk/manga/impl/util/BytecodeEditor.kt` |
| Preferences behave as `androidx.preference` does (summary providers, dependencies, groups, categories, screens, seek bars, two-state, list and text preferences). | `androidx/preference/Preference.java`, `androidx/preference/PreferenceGroup.java` (new), `androidx/preference/PreferenceCategory.java` (new), `androidx/preference/PreferenceScreen.java`, `androidx/preference/SeekBarPreference.java` (new), `androidx/preference/TwoStatePreference.java`, `androidx/preference/EditTextPreference.java`, `androidx/preference/ListPreference.java`, `androidx/preference/MultiSelectListPreference.java` |
| `Context` has the `registerReceiver` flags, `Context` and `PackageManager` stand-ins report the emulated app and the extensions the app installs, and `AndroidException` is a real class so `NameNotFoundException` can be thrown. Context file methods and PackageManager stand-in results: `openFileInput`, `openFileOutput`, `getFileStreamPath` and `getDir` work under the app's files and data folders (a name with a path separator is refused, as on Android), the `query*` methods return empty lists, an unsupported `PackageManager` call throws an `UnsupportedOperationException` that names it (not Kotlin's `NotImplementedError`), and `Context` methods that still return null (`getAssets`, `getContentResolver`, `getTheme`, `createPackageContext` and its neighbours) log a warning once. `Resources.getString(int)` stays a stub: the stand-in has no string table, and `DesktopResources` answers for the app. | `android/content/Context.java`, `android/content/Intent.java`, `android/content/pm/PackageInfo.java`, `android/content/pm/PackageManager.java`, `xyz/nulldev/androidcompat/androidimpl/FakePackageManager.java`, `xyz/nulldev/androidcompat/pm/ExternalPackages.kt` (new), `android/util/AndroidException.java` (new) |
| `Resources`, `Configuration` and `DisplayMetrics` answer from the desktop: `DesktopResources` is the `Resources` of `CustomContext`. The extension screen size (`DesktopResources.SCREEN_WIDTH_PIXELS` and `SCREEN_HEIGHT_PIXELS`) is one constant shared by `DisplayMetrics`, `DisplayManager` and the headless WebView's view rect. | `android/content/res/Resources.java`, `android/content/res/Configuration.java`, `xyz/nulldev/androidcompat/res/DesktopResources.java`, `android/util/DisplayMetrics.java`, `android/hardware/display/DisplayManager.java`, `xyz/nulldev/androidcompat/webkit/KcefWebViewProvider.kt` |
| View measure and layout: views fill the display (the extension WebView's size) until laid out or measured, are parented by `ViewGroup.addView`, take `MeasureSpec` as AOSP does, and draw nothing; the offscreen display, virtual displays, `ActivityManager` and `ImageReader` stand-ins serve the offscreen WebView. | `android/view/View.java`, `android/view/ViewGroup.java`, `android/view/Display.java`, `android/view/MotionEvent.java`, `android/view/Surface.java`, `android/hardware/display/DisplayManager.java`, `android/hardware/display/VirtualDisplay.java`, `android/app/ActivityManager.java`, `android/app/Presentation.java`, `android/media/ImageReader.java` |
| Graphics on `java.awt`: `Canvas` keeps the clip in `save()`, `Paint`, `Bitmap`, `Matrix`, `Rect`, `RectF`, `Path`, colour filters, `Typeface` and `Drawable` are Zoukan's, `BitmapFactory` decodes with ImageIO (AVIF through the FFmpeg reader, scaled when the reader ignores subsampling), and `ImageDecoder` reads through the same readers. | `android/graphics/Canvas.java`, `android/graphics/BitmapFactory.java`, `android/graphics/Bitmap.java`, `android/graphics/Paint.java`, `android/graphics/Matrix.java`, `android/graphics/Rect.java`, `android/graphics/RectF.java`, `android/graphics/Path.java`, `android/graphics/Typeface.java`, `android/graphics/ColorFilter.java`, `android/graphics/ColorMatrix.java`, `android/graphics/ColorMatrixColorFilter.java`, `android/graphics/ImageDecoder.java`, `android/graphics/drawable/Drawable.java`, `android/graphics/drawable/ColorDrawable.java`, `xyz/nulldev/androidcompat/graphics/ImageIoDecoding.kt`, `tachiyomi/decoder/ImageDecoder.kt`, `ca/mpreg/imagedecoder/ImageDecoder.kt` |
| Text and ICU stand-ins (`BreakIterator`, `Collator`, `Normalizer2`, `StringSearch`, spans, `Linkify`, `Patterns`) run on the JVM. | `android/icu/text/BreakIterator.java`, `android/icu/text/Collator.java`, `android/icu/text/Normalizer2.java`, `android/icu/text/RuleBasedCollator.java`, `android/icu/text/SearchIterator.java`, `android/icu/text/StringSearch.java`, `android/text/SpannableString.java`, `android/text/style/CharacterStyle.java`, `android/text/style/MetricAffectingSpan.java`, `android/text/util/Linkify.java`, `android/util/Patterns.java` |
| Handler, looper, bundle and toast stand-ins: `Toast`, `Bundle`, `HandlerThread`, `LocaleList` and the paused message queue shadow work without an Android runtime. An extension's default locale stays inside `LocaleList`; `Locale.getDefault()` is never changed. Interrupting a `HandlerThread` quits its looper (Android ignores interrupts; the poll still does, so a plain looper thread stops only with `quit()`). | `android/widget/Toast.java`, `android/os/Bundle.java`, `android/os/HandlerThread.java`, `android/os/LocaleList.java`, `android/os/shadows/ShadowPausedMessageQueue.java` |

`AndroidCompatInitializer` and `AndroidCompat.startApp` are called by `zoukan.desktop.ZoukanRuntime`, after it registers the Injekt modules.

The Noto fonts in Suwayomi's resources are not copied (37 MB); `Typeface` falls back to the JVM's
fonts.

The vendored files keep Suwayomi's formatting and are not formatted by Spotless, so a later sync
with Suwayomi stays a clean diff.

## Updating

For each file, diff it against the same file at Suwayomi commit
`d5215280158b9b78d2e3ca46312e39dccde6b97b` (not against an earlier Zoukan commit): the difference is
the Zoukan edits, and the `ZOUKAN` markers show where they are. Copy `AndroidCompat/src/main/java`
and `AndroidCompat/Config/src/main/java` from the new Suwayomi commit over `src/main/java`, then
reapply the marked edits, and set the commit above to the new one. Run
`./gradlew :desktop:test --tests 'zoukan.desktop.compat.VendoredFilesMarkedTest'` to check that no
marker was lost.
