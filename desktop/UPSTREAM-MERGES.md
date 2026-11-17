# Merging upstream Komikku

Zoukan's upstream base is Komikku commit `383ffaa81`. To share code with the desktop app, Zoukan
moved some upstream files. Git follows most of these moves in a merge, but a file that upstream
**adds** lands in the old path, and edits to the moved backup code may need porting by hand. Check
the points below after every upstream merge.

`./gradlew check` (and every Kotlin compile) runs `checkMultiplatformSourceLayout` in each
multiplatform module. It fails if a file is in `src/main/java`, `src/main/kotlin`,
`src/main/sqldelight`, `src/test/java` or `src/test/kotlin`, where the multiplatform plugin would
silently ignore it (`buildSrc/.../mihon/buildlogic/SourceLayoutCheck.kt`).

## Modules moved to the multiplatform layout

The relative path under the source folder is unchanged; only the source set changed.

| Upstream path | Zoukan path |
| --- | --- |
| `domain/src/main/java/` | `domain/src/commonMain/kotlin/` |
| `domain/src/test/java/` | `domain/src/androidUnitTest/kotlin/` |
| `data/src/main/java/` | `data/src/commonMain/kotlin/` |
| `data/src/main/sqldelight/` | `data/src/commonMain/sqldelight/` (schema and migrations) |
| `core/common/src/main/kotlin/` | `core/common/src/commonMain/kotlin/` |
| `core/archive/src/main/kotlin/` | `core/archive/src/androidMain/kotlin/` (one file in `commonMain`) |
| `*/src/main/AndroidManifest.xml` | `*/src/androidMain/AndroidManifest.xml` |

These files use Android-only APIs, so they went to `androidMain` instead of `commonMain`:

- `domain`: `tachiyomi/domain/storage/service/StorageManager.kt`
- `core/common`: `eu/kanade/tachiyomi/network/JavaScriptEngine.kt`,
  `network/interceptor/CloudflareInterceptor.kt`, `network/interceptor/WebViewInterceptor.kt`,
  `util/lang/StringExtensions.kt`, `util/storage/DiskUtil.kt`,
  `util/system/{DensityExtensions,DeviceUtil,GLUtil,ToastExtensions,WebViewUtil}.kt`,
  `exh/log/{EHNetworkLogging,Logging,ResettableLogger}.kt`, `tachiyomi/core/common/i18n/Localize.kt`,
  `tachiyomi/core/common/preference/AndroidPreferenceStore.kt`,
  `tachiyomi/core/common/storage/{AndroidStorageFolderProvider,UniFileExtensions,UniFileTempFileManager}.kt`,
  `tachiyomi/core/common/util/system/ImageUtil.kt`

A new upstream file goes to `commonMain` if it compiles there (the desktop target has no Android
SDK classes beyond `android-stubs`), otherwise to `androidMain`. A file that needs a desktop
version gets an `expect`/`actual` pair or a desktop counterpart in `desktopMain`.

## Backup code moved from `app` to the `backup` module

Moved from `app/src/main/java/` to `backup/src/commonMain/kotlin/`, same package:

- `eu/kanade/tachiyomi/data/backup/create/BackupOptions.kt`
- `eu/kanade/tachiyomi/data/backup/create/creators/`: `CategoriesBackupCreator`,
  `ExtensionStoresBackupCreator`, `FeedBackupCreator`, `MangaBackupCreator`,
  `PreferenceBackupCreator`, `SavedSearchBackupCreator`, `SourcesBackupCreator`
- `eu/kanade/tachiyomi/data/backup/models/` (all files, including `metadata/`)
- `eu/kanade/tachiyomi/data/backup/restore/RestoreOptions.kt`
- `eu/kanade/tachiyomi/data/backup/restore/restorers/`: `CategoriesRestorer`, `FeedRestorer`,
  `MangaRestorer`, `PreferenceRestorer`, `SavedSearchRestorer`
- `exh/EXHMigrations.kt`

Rewritten in `backup` (the `app` copy is deleted): `restore/restorers/ExtensionStoreRestorer.kt`.

Still in `app`, but most of their body now lives in `backup` (`BackupContentCreator`,
`BackupContentRestorer`, `BackupCodec`): `data/backup/create/BackupCreator.kt`,
`data/backup/restore/BackupRestorer.kt`, `data/backup/BackupDecoder.kt`. Upstream changes to these
three files, or to the old paths of the moved files, need porting into `backup` by hand.

The `backup` module cannot depend on `app`, so it keeps copies of two app constants:
`PINNED_SOURCES_PREF_KEY` (`SourcePreferences`) and `READING_MODE_MASK` (`ReadingMode.MASK`).
`app`'s `BackupUpstreamConstantsTest` fails if they drift.

`backup`'s project dependencies are `implementation`, except the ones its public signatures name:
`domain`, `data`, `source-api` and `core:common` stay `api`, the three i18n modules are
`implementation`. Keep that when merging `backup/build.gradle.kts`. `domain` keeps
`api(libs.sqldelight.android.paging)` as upstream has it (the paging source is in its public API).

## Android Gradle Plugin and the multiplatform modules

The modules that apply `mihon.library.kmp` (`domain`, `data`, `backup`, `core/common`,
`core/archive`), plus `source-api`, `source-local` and the `i18n*` modules, use
`com.android.library` with `androidTarget()`. The Android documentation's path for Kotlin
Multiplatform libraries is the `com.android.kotlin.multiplatform.library` plugin, and the
`com.android.library` combination is the one being phased out. Every shared module carries the
Android target, so an Android Gradle Plugin major that no longer supports it would break all of them,
and the desktop build with them.

Renovate does not propose Android Gradle Plugin majors (a rule in `renovate.json`); minor and patch
updates still come. Before raising the major, migrate these modules to the new plugin. Check what
upstream Komikku did first, since the `buildSrc` conventions come from upstream.

## Zoukan fixes in shared tracker code

The desktop app shares the tracker code in `app/src/main/java/eu/kanade/tachiyomi/data/track/`, so
these bugs are fixed there, for Android too. Each edit is marked with a `// ZOUKAN:` comment or a
`// ZOUKAN -->` / `// ZOUKAN <--` block. On a merge conflict in these files, keep the fix unless
upstream fixed the same bug; once it has, take upstream's version and remove the entry here.
The tests are in `desktop/src/test/kotlin/zoukan/desktop/track/`.

- `bangumi/dto/BGMSearch.kt`: `infobox` defaults to an empty list. Bangumi's API marks it optional,
  and one search result without it failed the whole search.
- `data/track/TrackerAuthExpired.kt` (new file): MyAnimeList's handling of a refused token (the
  `trackAuthExpired` preference and an `IOException` that says the login has expired) as helpers
  for the other OAuth trackers. Signing in again or signing out clears it, as for MAL.
- `kitsu/KitsuInterceptor.kt`, `shikimori/ShikimoriInterceptor.kt`, `bangumi/BangumiInterceptor.kt`,
  `mangaupdates/MangaUpdatesInterceptor.kt`: a 401 from the refresh or the call marks the login
  expired, and later calls fail before the network until the user signs in again (upstream stayed
  signed in and every call failed). A new token clears the flag. Missing tokens and a refresh answer
  that does not parse throw an `IOException` instead of `Exception`/`!!`, which OkHttp rethrew on its
  own thread.
- `kitsu/dto/KitsuSearch.kt`: Algolia's `startDate` is midnight UTC in epoch seconds; it is now
  formatted in UTC (in the local zone it showed the day before, west of UTC).
- `mangaupdates/dto/MURecord.kt`: a missing `type` or `year` gives an empty text, not `"null"`.
- `suwayomi/SuwayomiApi.kt`: the cover address joins the server address and the thumbnail path
  with one slash, and keeps an absolute thumbnail address.

## Full list

The exact list of moved files:

```bash
git diff --name-status -M50% 383ffaa81 HEAD | grep '^R'
```
