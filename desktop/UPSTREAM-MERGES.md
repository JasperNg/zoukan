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

## Full list

The exact list of moved files:

```bash
git diff --name-status -M50% 383ffaa81 HEAD | grep '^R'
```
