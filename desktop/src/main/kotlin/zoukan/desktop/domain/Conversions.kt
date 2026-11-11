package zoukan.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

// The conversions the Android app keeps in app/eu.kanade.domain (Manga.kt, Chapter.kt), which the
// desktop app cannot depend on.

fun Manga.toSManga(): SManga = SManga.create().also {
    it.url = url
    it.title = ogTitle
    it.artist = ogArtist
    it.author = ogAuthor
    it.description = ogDescription
    it.genre = ogGenre.orEmpty().joinToString()
    it.status = ogStatus.toInt()
    it.thumbnail_url = thumbnailUrl
    it.initialized = initialized
    it.memo = memo
}

fun Chapter.toSChapter(): SChapter = SChapter.create().also {
    it.url = url
    it.name = name
    it.date_upload = dateUpload
    it.chapter_number = chapterNumber.toFloat()
    it.scanlator = scanlator
    it.memo = memo
}

fun Chapter.copyFromSChapter(sChapter: SChapter): Chapter = copy(
    name = sChapter.name,
    url = sChapter.url,
    dateUpload = sChapter.date_upload,
    chapterNumber = sChapter.chapter_number.toDouble(),
    scanlator = sChapter.scanlator?.ifBlank { null }?.trim(),
    memo = sChapter.memo,
)
