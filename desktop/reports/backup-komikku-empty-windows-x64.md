# Backup round trip: komikku-empty.tachibk

windows-x64, 2026-11-10 00:33. Result: **pass**.

Restored into an empty profile, backed up again with every option, and compared field by field.

| Part | Items in the backup | Values compared |
|---|---|---|
| entries | 0 | 0 |
| categories | 0 | 1 |
| sources | 0 | 0 |
| app settings | 3 | 3 |
| source settings | 0 | 0 |
| extension stores | 0 | 0 |
| saved searches | 0 | 0 |
| feeds | 0 | 0 |

## Differences

None.

## Not compared

- entry lastModifiedAt, favoriteModifiedAt and version, chapter lastModifiedAt and version: kept by the database for sync and rewritten by every restore, on Android too
- database ids of categories (compared by name)
