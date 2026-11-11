# Zoukan extension compatibility

- Commit: 8601f14
- Platform: macos-arm64 (Mac OS X 27.2)
- Java: Eclipse Adoptium 21.0.13

Store: Keiyoushi (https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json), 1554 extensions, signing key 9add655a78e96c4e…
Mode: online, store jars where available

| Extension | Version | Install | Source tested | Popular | Latest | Search | Details | Chapters | Pages | First image | Error |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| all.mangadex | 1.6.3 | ok (jar) | MangaDex (en), 62 total | ok 20 | ok 73 | ok 20 | ok | ok 8 | ok 43 | ok image/jpeg 483 KB |  |
| all.mangaplus | 1.6.68 | ok (jar) | MANGA Plus by SHUEISHA (en), 9 total | ok 100 | ok 72 | ok 23 | ok | ok 6 | ok 53 | ok image/jpeg 769 KB |  |
| all.webtoons | 1.6.3 | ok (jar) | Webtoons.com (en), 7 total | ok 30 | ok 154 | ok 1 | ok | ok 45 | ok 149 | ok image/jpeg 31 KB |  |
| all.mangafire | 1.6.38 | ok (jar) | MangaFire (en), 7 total | ok 50 | ok 50 | ok 3 | ok | ok 654 | ok 64 | ok image/jpeg 177 KB |  |
| all.kagane | 1.6.42 | ok (jar) | Kagane (en), 18 total | fail |  |  |  |  |  |  | popular: IOException: Failed to bypass Cloudflare on kagane.moe |
| all.mangaball | 1.6.7 | ok (jar) | Manga Ball (en), 42 total | ok 24 | ok 24 | ok 24 | ok | ok 360 | ok 14 | ok image/jpeg 365 KB |  |
| en.weebcentral | 1.6.26 | ok (jar) | Weeb Central (en), 1 total | ok 32 | ok 32 | ok 32 | ok | ok 1198 | ok 57 | ok image/png 336 KB |  |
| en.asurascans | 1.6.71 | ok (jar) | Asura Scans (en), 1 total | ok 20 | ok 20 | ok 1 | ok | ok 348 | ok 19 | ok image/webp 114 KB |  |
| en.flamecomics | 1.6.3 | ok (jar) | Flame Comics (en), 1 total | ok 154 | ok 154 | ok 1 | ok | ok 312 | ok 64 | ok image/jpeg 31 KB |  |
| en.mangapill | 1.6.1 | ok (jar) | MangaPill (en), 1 total | ok 10 | ok 120 | ok 4 | ok | ok 405 | ok 92 | ok image/jpeg 160 KB |  |
| en.mangakatana | 1.6.2 | ok (jar) | MangaKatana (en), 1 total | ok 20 | ok 20 | ok 20 | ok | ok 2 | ok 51 | ok image/jpg 104 KB |  |
| en.mangabuddy | 1.6.39 | ok (jar) | MangaK (en), 1 total | ok 24 | ok 24 | ok 24 | ok | ok 205 | ok 3 | ok image/webp 63 KB |  |
| en.mangakakalot | 1.6.28 | ok (jar) | Mangakakalot (en), 1 total | ok 24 | ok 24 | fail: IOException: Failed to bypass Cloudflare on www.kakamanga.com | ok | ok 3877 | ok 20 | ok image/webp 139 KB |  |
| en.mangahere | 1.6.3 | ok (jar) | Mangahere (en), 1 total | ok 70 | ok 70 | ok 12 | ok | ok 421 | ok 19 | ok image/jpeg 85 KB |  |
| en.mangatown | 1.6.3 | ok (jar) | Mangatown (en), 1 total | ok 30 | ok 30 | ok 4 | ok | ok 400 | ok 45 | ok image/jpeg 201 KB |  |
| en.mangago | 1.6.45 | ok (jar) | Mangago (en), 1 total | ok 48 | ok 48 | ok 10 | ok | ok 207 | ok 32 | ok image/jpeg 138 KB |  |
| en.toonily | 1.6.77 | ok (jar) | Toonily (en), 1 total | ok 18 | ok 18 | ok 18 | ok | ok 276 | ok 44 | ok image/webp 23 KB |  |
| en.comix | 1.6.63 | ok (jar) | Comix (en), 1 total | ok 28 | ok 28 | ok 28 | fail |  |  |  | details: l: WebView render process crashed |
| en.tapastic | 1.6.6 | ok (jar) | Tapas (en), 1 total | ok 25 | ok 25 | ok 10 | ok | ok 63 | ok 107 | ok image/png 46 KB |  |
| en.templescan | 1.6.69 | ok (jar) | Temple Scan (en), 1 total | ok 304 | ok 304 | ok 1 | ok | ok 134 | ok 14 | ok image/jpeg 2752 KB |  |

Installed and loaded: 20 of 20
Passed every required step (popular, details, chapters, pages): 18 of 20
Without the 1 that need a manual Cloudflare check (all.kagane): 18 of 19

Latest and search are recorded but not required: some sources have no latest list or no text search.

Other checks on this machine: spotlessCheck, :desktop:compileKotlin and :extension-jvm:test pass;
`status` creates the encrypted database (first 16 bytes are not "SQLite format 3") with its key in
the macOS Keychain and reopens it on the next start.
