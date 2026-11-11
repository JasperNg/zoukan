# Backup round trip: komikku-real.tachibk

windows-x64, 2026-11-10 00:33. Result: **pass**.

Restored into an empty profile, backed up again with every option, and compared field by field.

| Part | Items in the backup | Values compared |
|---|---|---|
| entries | 12 | 312 |
| categories | 2 | 5 |
| sources | 3 | 3 |
| app settings | 11 | 11 |
| source settings | 0 | 0 |
| extension stores | 1 | 1 |
| saved searches | 1 | 1 |
| feeds | 1 | 1 |
| chapters |  | 6360 |
| history |  | 8 |

## Differences

None.

## Not compared

- entry lastModifiedAt, favoriteModifiedAt and version, chapter lastModifiedAt and version: kept by the database for sync and rewritten by every restore, on Android too
- database ids of categories (compared by name)
