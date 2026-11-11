# Backup round trip: synthetic.tachibk

macos-arm64, 2026-11-10 00:53. Result: **pass**.

Restored into an empty profile, backed up again with every option, and compared field by field.

| Part | Items in the backup | Values compared |
|---|---|---|
| entries | 4 | 104 |
| categories | 3 | 7 |
| sources | 3 | 3 |
| app settings | 10 | 10 |
| source settings | 2 | 5 |
| extension stores | 1 | 1 |
| saved searches | 2 | 2 |
| feeds | 3 | 3 |
| chapters |  | 50 |
| tracking |  | 22 |
| history |  | 8 |

## Differences

None.

## Not compared

- entry lastModifiedAt, favoriteModifiedAt and version, chapter lastModifiedAt and version: kept by the database for sync and rewritten by every restore, on Android too
- database ids of categories (compared by name)
