# Desktop parity checklist

The Phase 6 gate: what the Android app (Komikku) does and whether the desktop app does it. "Yes"
means the desktop app has it, with the Android data, preferences and backups, so a library moves
between the two. "Exception" rows are the proposed differences; each says why.

## Library, entries and reading

| Android feature | Desktop | Notes |
|---|---|---|
| Library grid, categories, search, sort, unread badges | Yes | Phase 3 |
| Library display modes, badges, continue reading button | Yes | Compact, comfortable, panorama, cover-only and list; columns; per-badge toggles |
| Library filters and sorts | Yes | Downloaded, unread, started, bookmarked, completed and per-tracker (tri-state); ten sorts with direction; per-category sort |
| Library selection actions | Yes | Right-click or Ctrl/Shift-click; categories, mark read/unread, download, delete downloads, remove |
| Edit categories: create, rename, delete, drag to reorder, hide, default category | Yes | Library > More > Edit categories, or Settings > Library > Edit categories as on Android. The default category is chosen at the top of the same screen or in Settings > Library; adding an entry uses it, as on Android |
| Library update (categories, skip rules, errors file) | Yes | Phase 5, also on a schedule |
| Library update errors screen (KMK) | Yes | Library > More > Library update errors: failed entries grouped by error, in the Android tables; retry, open, migrate and delete, one or many |
| Manga details, chapters, mark read, bookmark, download, delete | Yes | Phase 3, Phase 5 |
| Reader: paged, right to left, vertical, webtoon, continuous | Yes | Phase 4; keyboard, mouse and wheel |
| Reader settings sheet | Yes | Reading mode, General and Filter tabs (S) |
| Double pages, crop borders | Yes | Single, double or automatic in a wide window; inverted order |
| Reader filters | Yes | Brightness, color filter with blend modes, grayscale, inverted colors. Brightness below zero darkens the page, since a window cannot dim the screen |
| Skip read, filtered and duplicate chapters; auto scroll | Yes | Auto scroll on A |
| Reader page actions: set as cover, copy, save, first, second or combined page | Yes, changed | Right-click or long-press a page. Saves go to Pictures/Zoukan, in a folder per entry when that setting is on. Android's Share has no desktop share sheet, so Copy to clipboard stands in for it |
| Updates and History tabs | Yes | Phase 3 |
| More tab: downloaded only, incognito, download queue, categories, statistics, update errors, settings | Yes | The fifth rail tab, as Android's; Settings opens from it or with Ctrl/Cmd + comma. Downloaded only filters the library and Updates and shows a bar above every screen, as does incognito |
| Navigation rail: centered, tinted, Updates and extension update badges | Yes | As on an Android tablet |
| Upcoming (expected release calendar, "To be updated" list) | Yes | Updates > calendar button; the calendar beside the list, as on a tablet. Smart update shows the entries the next library update checks |
| Downloads queue, CBZ, encrypted CBZ, split tall images, survives restarts | Yes | Phase 5 |
| Local source: folders, CBZ, ZIP, EPUB, RAR, 7z, TAR | Yes | RAR 5 needs the system's `tar` |
| Backups (.tachibk) create, restore, automatic | Yes | Phase 5; round trip checked by tests |
| Statistics | Yes | Library > Statistics: entries, chapters, reading time, tracking |
| Merged entries (merge, edit, priority dedupe, per-entry download) | Yes | The Android `MergedSource`, shared |
| Migration (search other sources, copy or migrate with flags) | Yes | Manga > More > Migrate, custom cover flag included |
| Bulk migration (source priority, smart search, progress, manual pick) | Yes | Library selection > Migrate: sources in priority order, the Android options (data to migrate, extra keywords, hide unmatched or without newer chapters, advanced search, match by chapter number, smart search), then per-entry search, Don't migrate, Migrate now, and Migrate or Copy all with progress |
| Edit entry info, notes, custom cover | Yes | Manga > More > Edit; local entries write ComicInfo.xml and cover files, as on Android |
| Recommendations and related entries | Yes | Manga > More > Similar titles: the source's related titles, then AniList, MyAnimeList, MangaUpdates and the other recommendation sources |

## Settings

| Android feature | Desktop | Notes |
|---|---|---|
| Appearance: theme mode, color schemes, custom color and style, pure black | Yes, changed | The Android color schemes and settings. "Dynamic" (Material You) takes its colors from the phone's wallpaper, which desktops do not offer, so it shows the default scheme |
| Appearance: date format, relative timestamps | Yes | Updates, History and chapter dates |
| Appearance: hide Updates or History, navigation labels | Yes | On the navigation rail |
| Library: default category | Yes | "Always ask", the default category or a category |
| Library: update interval, categories to include and exclude, smart update, refresh metadata | Yes | |
| Library: update only while charging | Yes | Mains power, read from Windows or `pmset` on macOS; a desktop without a battery always qualifies |
| Library: update only on Wi-Fi or an unmetered network | No | Desktops give apps no reliable way to tell; the setting is kept for Android |
| Library: unread count on the Updates icon | Yes | Cleared when Updates opens |
| Library: mark duplicate read chapters as read | Yes | After reading a chapter, and after fetching new chapters |
| Library: chapter swipe actions | No | No swipe on desktop; the chapter list has right-click and selection actions |
| Downloads: delete after reading (nth to last), after marking read, bookmarked, excluded categories | Yes | As Android's: deleted when the reader closes |
| Downloads: download new chapters, with categories to include and exclude | Yes | |
| Downloads: download ahead while reading | Yes | When the open and the next chapter are downloaded |
| Downloads: only on Wi-Fi | No | As for library updates |
| Browse: suggestions, source categories, filter sources in categories, replace latest button, local source hidden folders | Yes | Settings > Browse. Source categories are made in Edit categories and set from a source's More menu (Android's long press) |
| Browse: hide Feed tab, Feed tab position, hide library entries in the feed and in sources | Yes | |
| Browse: expand related titles, related titles in the overflow menu, show home on related titles | No | They lay out the related-titles row inside the entry screen; the desktop shows related titles from the entry's More > Similar titles |
| Browse: extension stores, NSFW sources | Yes | Extension stores opens the same list as Browse > Stores |
| Data and storage: storage location, backup and restore, automatic backup frequency, last automatic backup | Yes | Android's frequencies, every hour to weekly. The desktop also opens the backup, download and local source folders |
| Data and storage: show restoring progress banner | No | The desktop shows a restore's progress in place of the backup buttons |
| Data and storage: storage usage, clear chapter cache, clear it on launch | Yes, changed | The desktop reader keeps online pages in memory, so its chapter cache on disk is the unpacked RAR, 7z and TAR chapters |
| Data and storage: clear page preview cache | No | No page previews on desktop |
| Data and storage: export library list (CSV) | Yes | The Android exporter's columns and quoting |
| Advanced: share crash logs | Yes, changed | Saved to a file you choose: the environment, crash reports and the log, as Android has no desktop share sheet |
| Advanced: reindex downloads, clear database | Yes | Clear database lists the sources with non-library entries, as Android, keeping entries with read chapters if asked |
| Advanced: clear cookies, clear WebView data, DNS over HTTPS, user agent and reset | Yes | WebView data is the built-in browser's cookies and cache; DNS over HTTPS and the user agent take effect after a restart, as on Android |
| Advanced: refresh library covers, reset per-series reader settings, update titles to match source, disallow non-ASCII filenames | Yes | |
| Advanced: include chapter URL hash, revoke trusted unknown extensions | Yes | |
| Advanced: enable delegated sources, log level | Yes | Read at start, as on Android |
| Advanced: hentai features toggle | Yes | Settings > E-Hentai |
| Advanced: encrypt database | Yes, changed | The desktop database is always encrypted (SQLCipher), so there is no switch |
| Advanced: notifications, battery optimization, hardware bitmap threshold, long strip decoding, display profile, extension installer, onboarding, debug info and menu | No | Android system or Android image decoder settings with nothing to set on desktop |
| Advanced: automatic app updates (Wi-Fi, charging) | No | The desktop updater has its own settings in About |
| Advanced: data saver, source blacklist, clean up downloaded chapters | No | Not ported yet: data saver needs a compression server in the reader and downloader, and SY keeps the clean-up hidden |

## Browse

| Android feature | Desktop | Notes |
|---|---|---|
| Sources, extensions, extension stores, global search | Yes | Phase 2 |
| Hidden sources and the sources filter (languages, per-source) | Yes | Browse > Sources > filter button, and Disable in a source's More menu, as Android's long press. The Sources list shows the enabled languages only, as on Android |
| Pinned, last used and source category sections | Yes | As Android's Sources list |
| Source filters and settings | Yes | Phase 2 |
| Saved searches | Yes | Chips above the listing; save from the filter panel; right-click deletes |
| Batch add (MangaDex, E-H, ExH, nH, 8Muses addresses, E-H Visited export) | Yes | Browse > Batch add button, instead of the More tab; the Android screen model and GalleryAdder, shared |
| Feed (global, latest/popular or a saved search) | Yes | Browse > Feed, and a per-source feed from the source's feed button |
| E-Hentai and ExHentai built-in sources | Yes | Settings > E-Hentai; sign in through the built-in browser or cookies |
| MangaDex and other delegated sources, MDList | Yes | MangaDex, NHentai, LANraragi, Pururin and 8Muses extensions run through the Android delegates; MDList signs in with OAuth |
| E-Hentai site settings (uconfig), favorites sync, gallery update worker | Yes | Settings > E-Hentai; gallery updates run in the background while the app is open |
| Metadata viewer and details block (E-Hentai, MangaDex, NHentai, Pururin, 8Muses, LANraragi) | Yes | Manga screen: rating, category, pages, uploader and tags by namespace (each searches the source); More info lists every field. Right-click copies a value, as a long press on Android |
| MangaDex settings: sign-in, preferred source, sync follows to the library, follow the library on MangaDex | Yes | Settings > MangaDex, shown when a MangaDex source is enabled, as on Android |
| Data saver (Bandwidth Hero, wsrv.nl) | Yes, changed | Settings > Data saver, for the reader and (optionally) downloads. Excluded sources are picked there, not from a long press in Browse |

## Trackers and sign-ins

| Android feature | Desktop | Notes |
|---|---|---|
| MyAnimeList, AniList, Kitsu, Shikimori, Bangumi, MangaUpdates | Yes | The Android tracker code, shared |
| Komga, Kavita, Suwayomi (enhanced trackers, bound on add) | Yes | |
| Tracking sheet: search, bind, status, chapter, score, dates, remove | Yes | Manga > Tracking |
| Update progress after reading, retry when offline | Yes | Retries every 15 minutes |
| "Ask" before updating progress | Yes | A dialog after a chapter is marked read |
| OAuth sign-in | Yes, changed | In the built-in browser, which catches the `komikku://` redirect, instead of the system browser with a loopback redirect: the trackers only accept Komikku's registered redirect. A pasted redirect from another browser also works |
| Cloudflare challenges | Yes | Hidden first, then the built-in browser |
| Site sign-in for a source | Yes | Browse > source > globe button opens the site; cookies are shared |

## Security, sync and connections

| Android feature | Desktop | Notes |
|---|---|---|
| App lock | Yes, changed | A password kept hashed in the OS secret store, instead of biometrics or the device PIN; lock when idle, days and times as on Android |
| Hide notification content | Yes | |
| Incognito mode and incognito extensions | Yes | No history, progress or tracking |
| Secure screen (block screenshots) | No | Desktop systems give apps no way to block screenshots |
| Data sync: SyncYomi and WebDAV | Yes | Settings > Data sync; the Android merge, on its interval and triggers |
| Data sync: Google Drive | No | Needs Komikku's Google OAuth client, which is a secret of the Android build |
| Discord Rich Presence | Yes, changed | Through the Discord app on the computer, so no Discord login is needed; the same options |

## Desktop only

| Feature | Notes |
|---|---|
| Keyboard shortcuts | Ctrl/Cmd + 1 to 5 tabs, Ctrl/Cmd + `,` settings, Ctrl/Cmd + Shift + U update, Escape back; listed in Settings |
| Tray icon and notifications | Phase 5 |

## Not on desktop (by design)

Home-screen widget, Android share sheet and deep links, biometrics, secure screen and Google Drive
sync. Backups keep their preferences, so nothing is lost moving back.
