-- The database schema at version 1: the state before data/src/commonMain/sqldelight/tachiyomi/migrations/1.sqm.
--
-- The repository has no schema snapshot older than 28.db, so this file is kept for the migration
-- tests (zoukan.desktop.db.OldDatabases) to build a version-1 database from text.
--
-- How it was derived: version 1 is the first database version of TachiyomiSY, whose versions
-- Komikku continues. Before SQLDelight, TachiyomiSY created its tables with hand-written SQL
-- (`DbOpenCallback` with `DATABASE_VERSION = 1` and the `*Table.createTableQuery` strings), and
-- SQLDelight's migrations 1.sqm to 13.sqm repeat that callback's later `onUpgrade` steps. The
-- statements below are those `createTableQuery` and index strings from TachiyomiSY's public history
-- at the commit that set `DATABASE_VERSION = 1`, with the column constants written out.
-- Reversing 1.sqm to 27.sqm by hand gives the same columns for every table except `merged` (which
-- 3.sqm drops without reading, so only the upstream source can say what it was) and the declared
-- types that 8.sqm and 13.sqm rewrite when they rebuild tables.
--
-- How it is proven: DatabaseMigrationTest builds this schema, runs the generated migrations 1.sqm to
-- 27.sqm and compares the result with 28.db (columns in order with type, NOT NULL, default and
-- primary key, foreign keys, indexes, views, triggers and rows). The only difference is what 14.sqm
-- leaves behind (history.last_read NOT NULL and a history_temp table), which that test pins. It
-- cannot see the declarations that 8.sqm and 13.sqm rewrite, only that every column they copy
-- exists here. OldDatabases explains the two steps it takes besides the migrations.
--
-- Statements are separated by a line holding only `;`.

CREATE TABLE mangas(
    _id INTEGER NOT NULL PRIMARY KEY,
    source INTEGER NOT NULL,
    url TEXT NOT NULL,
    artist TEXT,
    author TEXT,
    description TEXT,
    genre TEXT,
    title TEXT NOT NULL,
    status INTEGER NOT NULL,
    thumbnail_url TEXT,
    favorite INTEGER NOT NULL,
    last_update LONG,
    initialized BOOLEAN NOT NULL,
    viewer INTEGER NOT NULL,
    chapter_flags INTEGER NOT NULL
)
;
CREATE TABLE chapters(
    _id INTEGER NOT NULL PRIMARY KEY,
    manga_id INTEGER NOT NULL,
    url TEXT NOT NULL,
    name TEXT NOT NULL,
    scanlator TEXT,
    read BOOLEAN NOT NULL,
    bookmark BOOLEAN NOT NULL,
    last_page_read INT NOT NULL,
    chapter_number FLOAT NOT NULL,
    source_order INTEGER NOT NULL,
    date_fetch LONG NOT NULL,
    date_upload LONG NOT NULL,
    FOREIGN KEY(manga_id) REFERENCES mangas (_id)
    ON DELETE CASCADE
)
;
CREATE TABLE manga_sync(
    _id INTEGER NOT NULL PRIMARY KEY,
    manga_id INTEGER NOT NULL,
    sync_id INTEGER NOT NULL,
    remote_id INTEGER NOT NULL,
    library_id INTEGER,
    title TEXT NOT NULL,
    last_chapter_read INTEGER NOT NULL,
    total_chapters INTEGER NOT NULL,
    status INTEGER NOT NULL,
    score FLOAT NOT NULL,
    remote_url TEXT NOT NULL,
    start_date LONG NOT NULL,
    finish_date LONG NOT NULL,
    UNIQUE (manga_id, sync_id) ON CONFLICT REPLACE,
    FOREIGN KEY(manga_id) REFERENCES mangas (_id)
    ON DELETE CASCADE
)
;
CREATE TABLE categories(
    _id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    sort INTEGER NOT NULL,
    flags INTEGER NOT NULL,
    manga_order TEXT NOT NULL
)
;
CREATE TABLE mangas_categories(
    _id INTEGER NOT NULL PRIMARY KEY,
    manga_id INTEGER NOT NULL,
    category_id INTEGER NOT NULL,
    FOREIGN KEY(category_id) REFERENCES categories (_id)
    ON DELETE CASCADE,
    FOREIGN KEY(manga_id) REFERENCES mangas (_id)
    ON DELETE CASCADE
)
;
CREATE TABLE history(
    history_id INTEGER NOT NULL PRIMARY KEY,
    history_chapter_id INTEGER NOT NULL UNIQUE,
    history_last_read LONG,
    history_time_read LONG,
    FOREIGN KEY(history_chapter_id) REFERENCES chapters (_id)
    ON DELETE CASCADE
)
;
CREATE TABLE search_metadata(
    manga_id INTEGER NOT NULL PRIMARY KEY,
    uploader TEXT,
    extra TEXT NOT NULL,
    indexed_extra TEXT,
    extra_version INT NOT NULL,
    FOREIGN KEY(manga_id) REFERENCES mangas (_id)
    ON DELETE CASCADE
)
;
CREATE TABLE search_tags(
    _id INTEGER NOT NULL PRIMARY KEY,
    manga_id INTEGER NOT NULL,
    namespace TEXT,
    name TEXT NOT NULL,
    type INT NOT NULL,
    FOREIGN KEY(manga_id) REFERENCES mangas (_id)
    ON DELETE CASCADE
)
;
CREATE TABLE search_titles(
    _id INTEGER NOT NULL PRIMARY KEY,
    manga_id INTEGER NOT NULL,
    title TEXT NOT NULL,
    type INT NOT NULL,
    FOREIGN KEY(manga_id) REFERENCES mangas (_id)
    ON DELETE CASCADE
)
;
CREATE TABLE merged(
    merge_id INTEGER NOT NULL,
    manga_id INTEGER NOT NULL
)
;
CREATE INDEX mangas_url_index ON mangas(url)
;
CREATE INDEX library_favorite_index ON mangas(favorite) WHERE favorite = 1
;
CREATE INDEX chapters_manga_id_index ON chapters(manga_id)
;
CREATE INDEX chapters_unread_by_manga_index ON chapters(manga_id, read) WHERE read = 0
;
CREATE INDEX history_history_chapter_id_index ON history(history_chapter_id)
;
CREATE INDEX search_metadata_uploader_index ON search_metadata(uploader)
;
CREATE INDEX search_metadata_indexed_extra_index ON search_metadata(indexed_extra)
;
CREATE INDEX search_tags_manga_id_index ON search_tags(manga_id)
;
CREATE INDEX search_tags_namespace_name_index ON search_tags(namespace, name)
;
CREATE INDEX search_titles_manga_id_index ON search_titles(manga_id)
;
CREATE INDEX search_titles_title_index ON search_titles(title)
;
CREATE INDEX merged_merge_id_index ON merged(merge_id)
