# Desktop parity checklist

The Phase 6 gate: what the Android app (Komikku) does and whether the desktop app does it. "Yes"
means the desktop app has it, with the Android data, preferences and backups, so a library moves
between the two. "Exception" rows are the proposed differences; each says why.

## Library, entries and reading

| Android feature | Desktop | Notes |
|---|---|---|
| Library grid, categories, search, sort, unread badges | Yes | Phase 3 |
| Library search syntax (SY) and global search row | Yes | SY's query syntax: quotes, `-` to exclude, `namespace:tag` against E-Hentai and other gallery tags and titles, `id:`, `src:` (`src:local`), tracker names and statuses, and KMK's `mangadex-dmca`. "Search for … globally" above the results opens a global search |
| Library display modes, badges, continue reading button | Yes | Compact, comfortable, panorama, cover-only and list; columns; per-badge toggles; language as a flag ("Use language icon", KMK) and the source icon badge (KMK) |
| Library: show hidden categories (KMK) | Yes | Filter, sort and display > Display > Show hidden categories |
| Library grouping by source, status or tracking status (SY) | Yes | Filter, sort and display > Group; the tabs become the groups, and updating a tab updates its entries |
| Library filters and sorts | Yes | Downloaded, unread, started, bookmarked, completed, Lewd (SY), categories to include and exclude (KMK) and per-tracker (tri-state, any included tracker as KMK); ten sorts with direction; per-category sort. Customized update frequency shows with the "outside the release period" update restriction (Android shows it only in debug builds) |
| Tag sorting (SY) | Yes | Settings > Library > Sorting > Tag sorting tags: add, order and delete tags; then Sort > Tag sorting, offered once there are tags, as on Android |
| Library selection actions | Yes | Right-click or Ctrl/Shift-click; categories, mark read/unread, download (next chapters, unread or bookmarked), delete downloads, update the selected entries (KMK), migrate, remove; More holds Merge (KMK), Clean titles, Find recommendations, Add to MangaDex follows and Reset info (SY), each shown when it applies |
| Find recommendations over a selection (SY) | Yes | Library selection > More > Find recommendations, with two or more entries: the services to search and Hide results already in your library, then a progress dialog, then Common recommendations ranked by how many of the entries recommend each result ("+N") |
| Edit categories: create, rename, delete, drag to reorder, hide, default category | Yes | Library > More > Edit categories, or Settings > Library > Edit categories as on Android. The default category is chosen at the top of the same screen or in Settings > Library; adding an entry uses it, as on Android |
| Library update (categories, skip rules, errors file) | Yes | Phase 5, also on a schedule. A library update stops partway when a backup restore starts, and a restore waits for a running automatic backup (Android orders these as WorkManager jobs) |
| Library dynamic category updates (SY `groupLibraryUpdateType`) | Yes | Settings > Library: updating a source, status or tracking tab updates the whole library, or that tab's entries, or that tab's entries except when ungrouped |
| Library: Sync library in the overflow while data sync is on (SY) | Yes | Library > More > Sync library starts a data sync, or says one is running |
| Library update errors screen (KMK) | Yes | Library > More > Library update errors: failed entries grouped by error, in the Android tables; retry, open, migrate and delete, one or many |
| Manga details, chapters, mark read, bookmark, download, delete | Yes | Phase 3, Phase 5 |
| Reader: paged, right to left, vertical, webtoon, continuous | Yes | Phase 4; keyboard, mouse and wheel |
| Reader settings sheet | Yes | Reading mode, General and Filter tabs (S) |
| Reader menu: chapter navigator, bottom buttons, bookmark, chapter list | Yes, changed | As Komikku's: previous and next chapter either side of a floating page slider, standing on the side in vertical modes when the window is taller than wide (or always, with that setting). Bottom row: chapters (C), WebView, browser, reading mode, scale type (in place of rotation), crop borders, page layout and settings; the choice dialogs are menus. Bookmark from the top bar or B |
| Settings > Reader: tap zones and inverting, zoom start, double tap zoom, disable zoom in or out, page transitions | Yes | Android's layouts (L shaped, Kindle-ish, Edge, Right and left, Disabled) and smaller tap zones; also in the reader's settings sheet |
| Reader scale types: fit screen, stretch, fit width, fit height, original size, smart fit | Yes | Android's `imageScaleType`, so the choice moves with backups; the old desktop-only setting is carried over once. Stretch fills the window and pans the overflow, Smart fit fits a tall page's width and a wide page's height |
| Webtoon smart scale on wide screen (KMK): 4:3, 3:2, 16:9, 20:9 | Yes | In webtoon mode, and in Long strip with gaps when "Apply Smart scale to Long strip with gaps" is on; Settings > Reader > Webtoon and the reader's settings sheet |
| Reader theme Automatic | Yes | Gray in a dark app theme and white in a light one; in the paged modes each single page gets its margins' color (or a top to bottom gradient), as Android's chooseBackground |
| Show tap zones overlay | Yes | Settings > Reader > Display: the tap zones show when the reader opens, and the first time it is ever used; they also show when the tap zones change while reading. Any click or key hides them |
| Split or rotate wide pages, center margin, shift double pages | Yes | Paged and webtoon, with invert; wide pages stand alone in double page mode, as SY. Shift with D or the settings sheet |
| Auto webtoon mode, preserve reading position | Yes | SY's tag and source rules |
| Page loading: preload size, reader threads, aggressive loading, instant retry, retry all, boost page | Yes | Retry all is R, boost page Shift+R |
| Show reading mode, keep screen on, flash on page change, smooth auto scroll, show actions on long tap | Yes, changed | Keep screen on uses SetThreadExecutionState on Windows and `caffeinate` on macOS. Long tap gates the long press only; a right-click always opens page actions |
| Reader: volume keys, display cutout, orientation, fullscreen (system bars) | No | Phone hardware and gestures; the desktop has F11 for full screen and Ctrl + wheel to zoom |
| Reader landscape zoom (KMK `landscapeZoom`) | No | Android's image scale preference for wide pages (Fit or Double); not phone hardware, so it would be meaningful here, but the desktop does not have it |
| Webtoon pinch-zoom toggle (`webtoonPinchToZoomEnabled`) | Yes | Android's preference; gates Ctrl/Cmd + wheel zoom in webtoon mode; Settings > Reader and the reader sheet |
| Double pages, crop borders | Yes | Single, double or automatic in a wide window; inverted order |
| Reader filters | Yes | Brightness, color filter with blend modes, grayscale, inverted colors. Brightness below zero darkens the page, since a window cannot dim the screen |
| Skip read, filtered and duplicate chapters; auto scroll | Yes | Auto scroll on A |
| Reader page actions: set as cover, copy, save, first, second or combined page | Yes, changed | Right-click or long-press a page. Saves go to Pictures/Zoukan, in a folder per entry when that setting is on. Android's Share has no desktop share sheet, so Copy to clipboard stands in for it |
| Updates and History tabs | Yes | Phase 3 |
| Updates filters: downloaded, unread, started, bookmarked, hide excluded scanlators | Yes | Updates > filter button; the Android preferences, so they move with backups |
| History filters: unfinished entries, unfinished chapters, not in library; panorama covers (KMK) | Yes | History > filter button; the Android preferences, so they move with backups |
| History: selection, remove and add to library | Yes | The select button, Ctrl/Cmd + click or right click selects, Shift + click selects a range; remove the selected rows or every chapter of their entries. Rows not in the library have Add to library, with the duplicate check and categories |
| Updates: selection and per-chapter download | Yes | Ctrl/Cmd + click or right click selects, Shift + click selects a range; mark read, bookmark, download or delete the selected chapters; each row has its download button |
| More tab: downloaded only, incognito, download queue, categories, statistics, update errors, settings | Yes | The fifth rail tab, as Android's; Settings opens from it or with Ctrl/Cmd + comma. Downloaded only filters the library and Updates and shows a bar above every screen, as does incognito |
| Navigation rail: centered, tinted, Updates and extension update badges | Yes | As on an Android tablet |
| Updates grouped by entry (KMK) | Yes | Several new chapters of one entry on the same day fold into one row; its caret button expands them. Each day's header shows its chapter count. Selecting a folded row selects all of its chapters |
| Upcoming (expected release calendar, "To be updated" list) | Yes | Updates > calendar button; the calendar beside the list, as on a tablet. Smart update shows the entries the next library update checks |
| Downloads queue, CBZ, encrypted CBZ, split tall images, survives restarts | Yes | Phase 5. A download follows its chapter when the source renames it or changes its scanlator, as Android. With password protection on, a download fails (it is never saved unencrypted) when the OS secret store cannot be read; Android's Keystore does not fail this way |
| Download queue: sort by upload date or chapter number, move to top or bottom, move or cancel a series | Yes | Downloads > sort button sorts within each source, as Android; each download's More menu has the moves and cancels |
| Local source: folders, CBZ, ZIP, EPUB, RAR, 7z, TAR | Yes | RAR 5 needs the system's `tar` |
| Backups (.tachibk) create, restore, automatic | Yes | Phase 5; round trip checked by tests |
| Create backup choices: entries, chapters, tracking, history, categories, non-library entries, custom info, saved searches and feeds, app, extension store, source and private settings | Yes | Settings > Data and storage > Create backup opens Android's screen, then asks where to save the file (the backup folder by default). Private settings (sign-ins and tokens) no longer need the `--private` command line flag |
| Restore backup choices and the missing sources and trackers warning | Yes | Settings > Data and storage > Restore backup, after picking the file: Android's screen, with what to restore, the sources the backup needs that are not installed and the trackers it uses that are not signed in, or why the file is not a backup |
| Statistics | Yes | Library > Statistics: entries, chapters, reading time, tracking |
| Statistics: include all read entries, ignore non-library entries (SY) | Yes | Statistics > More: counts the read entries that are not in the library too, and back |
| Merged entries (merge, edit, priority dedupe, per-entry download) | Yes | The Android `MergedSource`, shared |
| Migration (search other sources, copy or migrate with flags) | Yes | Manga > More > Migrate; the Custom cover checkbox shows when the entry has a custom cover file, as on Android, and the flags are stored the same way |
| Bulk migration (source priority, smart search, progress, manual pick) | Yes | Library selection > Migrate: sources in priority order, the Android options (data to migrate, extra keywords, hide unmatched or without newer chapters, advanced search, match by chapter number, smart search), then per-entry search, Don't migrate, Migrate now, and Migrate or Copy all with progress |
| Edit entry info, notes, custom cover | Yes | Manga > More > Edit; local entries write ComicInfo.xml and cover files, as on Android. The thumbnail URL and KMK's Fill from tracker (choosing the tracker when several track the entry) are in the same dialog |
| Entry title, author, artist and source menus (KMK) | Yes | A click on the title, author or artist searches globally, and a click on the source opens it (its feed with "Use new source navigation"). A right click or long press opens Library search, Global search and Copy to clipboard |
| Entry tag menu | Yes | A tag's click opens Search (the browse screen the entry was opened from, turning on the source's filter of that name, or else the library), Global search and Copy to clipboard |
| Images inside descriptions | Yes, changed | Markdown image syntax renders inline, http/https only (file: and network paths from source-controlled text are not loaded); broken images degrade to a link; Settings > Appearance |
| Open folder, Clear manga (KMK) | Yes | Manga > More. Open folder opens the entry's download folder (a local entry's own folder) in Explorer or Finder; a merged entry asks which entry. Clear manga deletes the downloaded data, the chapters and history from the database, or both |
| Chapter settings Display tab | Yes | Filter and sort > Display: the source's title or "Chapter <number>" in the chapter list |
| Entry update frequency (smart update interval) | Yes | Manga > More > Update frequency, for library entries: the predicted next update and, with "Predict next release time" on, a custom interval or none, as Android's dialog |
| Cover actions: save, share, change, delete custom cover | Yes, changed | Click the cover or Manga > More > Cover. Save goes to Pictures/Zoukan; Android's Share has no desktop share sheet, so Copy to clipboard stands in for it |
| Panorama cover on the entry page (KMK `usePanoramaCoverMangaInfo`) | Yes | Wide covers (0.75 ratio threshold, as Android) render 3:2 in the entry header; Settings > Appearance > Entry page |
| Resume/Read button docking | Yes | Drag the button left/right to dock it (Android's `reading_button_position`, default End) |
| Duplicate check on adding to the library | Yes | Same-title library entries, and entries bound to the same tracker (upstream v1.14.0's `track_dupes`, inherited with the shared data module), each to open or migrate, or add anyway; on the manga screen and on a right-click in Browse |
| Add or remove from Browse (long press) | Yes, changed | Right-click a browse result, as Android's long press: removing asks first, adding checks duplicates and categories |
| Right-click mirrors Android's long press | Yes | Wherever Android has tap + long-press, left click does the tap action and right click the long-press: browse/feed/global-search cards, related titles and recommendations, migration and duplicate rows, extension rows, tracker sheet labels and search results, entry-page In-library (edit categories) and WebView (copy link) buttons |
| Fetch metadata and chapters when adding to library (KMK) | Yes | Settings > Library; applies to entries added from Browse, as on Android |
| Missing chapter indicators, hide setting | Yes | "Missing N chapters" between chapter rows; Settings > Library > Hide missing chapter indicators |
| Share entry link | Yes, changed | Manga > More > Copy link, as there is no desktop share sheet |
| Download next chapters: 1, 5, 10, 25, custom, unread, bookmarked | Yes | Custom asks for a number of unread chapters |
| Recommendations and related entries | Yes | Manga > More > Similar titles: the source's related titles, then AniList, MyAnimeList, MangaUpdates and the other recommendation sources |

## Settings

| Android feature | Desktop | Notes |
|---|---|---|
| Appearance: theme mode, color schemes, custom color and style, pure black | Yes, changed | The Android color schemes and settings. "Dynamic" (Material You) takes its colors from the phone's wallpaper, which desktops do not offer, so it shows the default scheme |
| Appearance: custom theme palettes (KMK) | Yes | The custom color picker lists KMK's named palettes (Sunset to PICO-8); a color picks it, and Confirm Color sets it |
| Appearance: cover-based theme on the entry page and reader (KMK `themeCoverBased`) | Yes | The entry page and the reader take their colors from the entry's cover; Settings > Appearance |
| Appearance: expand all search filters by default (SY `expandFilters`) | Yes | Settings > Appearance > Browse, as Android's fork settings; the source filter panel opens its sort and group filters |
| Appearance: date format, relative timestamps | Yes | Updates, History and chapter dates |
| Appearance: hide Updates or History, navigation labels | Yes | On the navigation rail |
| Appearance: app language | No | Zoukan is English only: there is no language setting, and the app shows English whatever the system language is. Source language filters (which sources and extensions to show) are unchanged |
| Library: default category | Yes | "Always ask", the default category or a category |
| Library: update interval, categories to include and exclude, smart update, refresh metadata | Yes | |
| Library: update only while charging | Yes | Mains power, read from Windows or `pmset` on macOS; a desktop without a battery always qualifies |
| Library: update only on Wi-Fi or an unmetered network | No | Desktops give apps no reliable way to tell; the setting is kept for Android |
| Library: unread count on the Updates icon | Yes | Cleared when Updates opens |
| Library: mark duplicate read chapters as read | Yes | After reading a chapter, and after fetching new chapters |
| Library: show empty categories during search/filtering | Yes | Off, as Android: categories with no match leave the tabs while searching or filtering |
| Library: chapter swipe actions | Yes | Settings > Library > Chapter swipe; drag a chapter row sideways with the mouse or a touch screen |
| Downloads: delete after reading (nth to last), after marking read, bookmarked, excluded categories | Yes | As Android's: deleted when the reader closes |
| Downloads: download new chapters, with categories to include and exclude | Yes | |
| Downloads: download ahead while reading | Yes | When the open and the next chapter are downloaded |
| Downloads: parallel download limits per source and per page (v1.13.6) | Yes | How many downloads a source runs at once and how many pages of a chapter download at once; Settings > Downloads |
| Downloads: only on Wi-Fi | No | As for library updates |
| Downloads: download cache renew interval | Yes | How often the download folders are read again; Manual waits for Advanced > Reindex downloads |
| Browse: suggestions, source categories, filter sources in categories, replace latest button, local source hidden folders | Yes | Settings > Browse. Source categories are made in Edit categories and set from a source's More menu (Android's long press) |
| Browse: hide Feed tab, Feed tab position, hide library entries in the feed and in sources | Yes | |
| Browse: expand related titles, related titles in the overflow menu | Yes | The related titles row under the entry's description, with See all opening Similar titles. Show home on related titles is Android's and not shown; neither is Top aligned cover, which upstream applies to the phone layout only, so the tablet layout the desktop follows never receives it (the preference still moves with backups) |
| Browse: extension stores, NSFW sources | Yes | Extension stores opens the same list as Browse > Stores |
| Data and storage: storage location, backup and restore, automatic backup frequency, last automatic backup | Yes | Android's frequencies, every hour to weekly. The desktop also opens the backup, download and local source folders |
| Data and storage: show restoring progress banner | No | The desktop shows a restore's progress in place of the backup buttons |
| Data and storage: storage usage, clear chapter cache, clear it on launch | Yes, changed | The desktop reader keeps online pages in memory, so its chapter cache on disk is the unpacked RAR, 7z and TAR chapters |
| Page previews (E-Hentai, nhentai, LANraragi) | Yes | On the entry page, Appearance > Page preview rows; More previews opens them all and a preview opens the reader at its page. Previews are kept in memory, so there is no cache to clear |
| Data and storage: export library list (CSV) | Yes | The Android exporter's columns and quoting |
| Advanced: share crash logs | Yes, changed | Saved to a file you choose: the environment, crash reports and the log, as Android has no desktop share sheet |
| Advanced: reindex downloads, clear database | Yes | Clear database lists the sources with non-library entries, as Android, keeping entries with read chapters if asked |
| Advanced: clear cookies, clear WebView data, DNS over HTTPS, user agent and reset | Yes | WebView data is the built-in browser's cookies and cache; DNS over HTTPS and the user agent take effect after a restart, as on Android |
| Advanced: refresh library covers, reset per-series reader settings, update titles to match source, disallow non-ASCII filenames | Yes | |
| Advanced: include chapter URL hash, revoke trusted unknown extensions | Yes | |
| Advanced: enable delegated sources, log level | Yes | Read at start, as on Android |
| Advanced: hentai features toggle | Yes | Settings > E-Hentai |
| Advanced: encrypt database | Yes, changed | The desktop database is always encrypted (SQLCipher), so there is no switch |
| Settings search | Yes, changed | A field over the settings list matches every setting's title and summary. The results are the settings themselves, so they can be changed there; each one's page and group opens the page at it, highlighted, as on Android |
| Advanced: onboarding guide | Yes | Opens the first-run setup again |
| Advanced: notifications, battery optimization, hardware bitmap threshold, long strip decoding, display profile, extension installer, debug info and menu | No | Android system or Android image decoder settings with nothing to set on desktop |
| Advanced: automatic app updates (Wi-Fi, charging) | No | The desktop updater has its own settings in About |
| First-run setup (onboarding): theme, storage location, getting started guide, restore a backup | Yes, changed | Shown over the app until finished, as on Android. Android's permissions step has nothing to ask on a desktop. Someone who used Zoukan before it existed, with a library already, does not see it |
| What's new: "Updated to" dialog after an update, release notes screen, About > What's new | Yes | The notes of the running version's GitHub release (desktop-v tag), in Markdown |
| Advanced: clean up downloaded chapters | Yes | Orphaned and unfinished downloads always; read chapters and entries no longer in the library when ticked. Entries with queued chapters are skipped |
| Advanced: source blacklist (SY) | Yes | Advanced > Developer tools. With E-Hentai on, hides the E-Hentai extension and its sources, which the built-in ones replace; read at start, as on Android |

## Browse

| Android feature | Desktop | Notes |
|---|---|---|
| Sources, extensions, extension stores, global search | Yes | Phase 2 |
| Extension stores over plain http | Exception | A store, its extension list and its downloads must use https (this computer excepted, for a local test store), and a redirect from https to http is refused. Komikku on Android allows http stores; over http anyone on the network could swap a store's signing key and code. An http store fails with a message that says it must use https. The rule is in the shared data module (`requireSecureStoreUrl`), so it also applies to Android builds of this tree, where the extension list only logs a failing store |
| Hidden sources and the sources filter (languages, per-source) | Yes | Browse > Sources > filter button, and Disable in a source's More menu, as Android's long press. The Sources list shows the enabled languages only, as on Android |
| Pinned, last used and source category sections | Yes | As Android's Sources list |
| Search box over the Sources list (KMK) | Yes | Above the list: matches a source's name, its extension's name or its id; commas separate terms, any of which may match. The desktop also has the search over the Extensions list and the Migration screens |
| Source filters and settings | Yes | Phase 2 |
| Saved searches | Yes | Chips above the listing; save from the filter panel; right-click deletes |
| Source display modes: comfortable, panorama (KMK) and compact grid, list | Yes | The source's display mode button, as Android's toolbar; the Android preference, shared by every source. Columns follow the library's column setting |
| Enhanced E-Hentai view (detailed list of galleries) | Yes | Settings > E-Hentai: E-Hentai and ExHentai results show the cover, uploader, rating, category, language, pages and date posted |
| Panorama cover flow in feed and global search rows (KMK) | Yes | Settings > Appearance > Browse: wide covers show wide. The entry page has its own panorama cover; upstream's panorama covers in dialogs (`usePanoramaCoverAlways`) is Android's and not ported |
| Batch add (MangaDex, E-H, ExH, nH, 8Muses addresses, E-H Visited export) | Yes | Browse > Batch add button, instead of the More tab; the Android screen model and GalleryAdder, shared |
| Feed (global, latest/popular or a saved search) | Yes | Browse > Feed, and a per-source feed from the source's feed button |
| Feed order (global and per source, KMK) | Yes | The feed's reorder button: drag the saved feeds, or move them with the arrows |
| Extensions: Updates pending and Update all | Yes | Installed extensions with updates come first under Updates pending, whose Update all button updates each shown one |
| Extension details | Yes | Click an installed extension: version, language, age rating, store, uninstall, incognito, clear cookies, and each source with its on/off switch and settings |
| Browse > Migrate (migrate by source) | Yes | The sources of the library's entries with their counts, sorted by name or count; pick a source's entries and migrate them with the bulk migration |
| Bulk add to library (KMK) | Yes | The select button in a source, the global search or the feed, then click or right-click results and Add to library; asks about titles already in the library, then uses the default category or asks |
| Open an entry from its address (deep link) | Yes, changed | Browse > link button, or paste the address into Search all sources: the source that knows it opens the entry or the chapter, else a global search for it. The desktop has no system link handler, so the address is pasted |
| Global search filters: pinned sources only, has results (KMK) | Yes | Chips above the results. The global search uses the enabled sources, as on Android |
| E-Hentai and ExHentai built-in sources | Yes | Settings > E-Hentai; sign in through the built-in browser or cookies |
| MangaDex and other delegated sources, MDList | Yes | MangaDex, NHentai, LANraragi, Pururin and 8Muses extensions run through the Android delegates; MDList signs in with OAuth |
| MangaDex Random and Follows (SY) | Yes | The buttons at the top of a MangaDex source's filter panel: Random opens a random entry's search (`id:`), Follows lists the signed-in account's follows with the display modes and bulk add |
| E-Hentai site settings (uconfig), favorites sync, gallery update worker | Yes | Settings > E-Hentai; gallery updates run in the background while the app is open |
| E-Hentai gallery update restrictions | Yes, changed | Charging waits for mains power, as the library update does. Wi-Fi and unmetered network stay in the setting for Android |
| Metadata viewer and details block (E-Hentai, MangaDex, NHentai, Pururin, 8Muses, LANraragi) | Yes | Manga screen: rating, category, pages, uploader and tags by namespace (each opens the tag menu); More info lists every field. Right-click copies a value, as a long press on Android |
| MangaDex settings: sign-in, preferred source, sync follows to the library, follow the library on MangaDex | Yes | Settings > MangaDex, shown when a MangaDex source is enabled, as on Android |
| Data saver (Bandwidth Hero, wsrv.nl) | Yes, changed | Settings > Data saver, for the reader and (optionally) downloads. Excluded sources are picked there, not from a long press in Browse |

## Trackers and sign-ins

| Android feature | Desktop | Notes |
|---|---|---|
| MyAnimeList, AniList, Kitsu, Shikimori, Bangumi, MangaUpdates | Yes | The Android tracker code, shared |
| Komga, Kavita, Suwayomi (enhanced trackers, bound on add) | Yes | Suwayomi's delete downloaded chapters on the server toggle is in the extension's own preference screen, as on Android |
| Tracking sheet: search, bind, status, chapter, score, dates, remove | Yes | Manga > Tracking |
| Update progress after reading, retry when offline | Yes | Retries every 15 minutes |
| "Ask" before updating progress | Yes | A dialog after a chapter is marked read |
| OAuth sign-in | Yes, changed | In the built-in browser, which catches the `komikku://` redirect, instead of the system browser with a loopback redirect: the trackers only accept Komikku's registered redirect. A pasted redirect from another browser also works |
| Cloudflare challenges | Yes, changed | Hidden first, then the built-in browser. Parallel requests to one host share one challenge attempt: a request that waited reuses the new clearance, or fails if that attempt failed. Android lets each request solve its own |
| Site sign-in for a source | Yes | Browse > source > globe button opens the site; cookies are shared |

## Security, sync and connections

| Android feature | Desktop | Notes |
|---|---|---|
| App lock | Yes, changed | A password kept hashed in the OS secret store, instead of biometrics or the device PIN; lock when idle, days and times as on Android. Locking keeps the screen, its back stack and saved state, but closes open dialogs and drops text not yet saved on the screen behind it; Android keeps them under the unlock screen. If the OS secret store cannot be read while the lock is on, the app stays locked |
| Hide notification content | Yes | |
| Incognito mode and incognito extensions | Yes | No history, progress or tracking |
| Secure screen (block screenshots) | No | Desktop systems give apps no way to block screenshots |
| Data sync: SyncYomi and WebDAV | Yes, changed | Settings > Data sync; the Android merge, on its interval and triggers. Sync (and the command line `backup restore`) never adds an extension store or changes a store's signing key, which Android's sync does: the skipped stores are named in a notification, to add in Browse > Extensions > Stores or through a Settings restore, which asks first |
| Data sync: Google Drive | No | Needs Komikku's Google OAuth client, which is a secret of the Android build |
| Discord Rich Presence | Yes, changed | Through the Discord app on the computer, so no Discord login is needed; the same options |

## Desktop only

| Feature | Notes |
|---|---|
| Keyboard shortcuts | Ctrl/Cmd + 1 to 5 tabs, Ctrl/Cmd + `,` settings, Ctrl/Cmd + Shift + U update, Ctrl/Cmd + F search, Ctrl/Cmd + A select all, Escape back; in the reader, Right and Left turn pages (flipped in right to left mode or by inverted tap zones), Down and Up scroll or turn (flipped by vertically inverted tap zones), J and K scroll down and up whatever the tap zones do; listed in Settings, and the reader's own shortcuts in the reader itself (press /) |
| Desktop conventions | Scrollbars on lists and grids, the window opens where it was closed (minimum 800 x 560), tooltips on icon buttons |
| Tray icon and notifications | Phase 5 |
| Install missing extensions after a restore | Android has no such action. After a restore, the Restore backup screen stays open with the results; it refreshes the store lists, matches the library's sources that are still not installed to the stores' extensions by source id (the store copy Browse lists), and offers Install missing extensions. A confirmation lists the extensions (name, store, 18+), those installed but untrusted and the sources no store offers. Each installs through the normal install, so the signature checks, signer pin, https rule and untrusted flow apply; an unknown signer is never trusted by itself, and the summary says to trust it in Browse > Extensions. Failures are listed and do not stop the rest. Android's restore screen closes when the restore starts; the desktop one closes with its Close button |

## Not on desktop (by design)

Home-screen widget, Android share sheet and system link handling (paste the address in Browse instead), biometrics, secure screen and Google Drive
sync. Backups keep their preferences, so nothing is lost moving back.
