package zoukan.desktop.exh

import androidx.compose.ui.graphics.Color
import dev.icerock.moko.resources.StringResource
import exh.metadata.metadata.base.RaisedTag
import exh.source.PURURIN_SOURCE_ID
import exh.source.TSUMINO_SOURCE_ID
import exh.source.eHentaiSourceIds
import exh.source.lanraragiSourceIds
import exh.source.mangaDexSourceIds
import exh.source.nHentaiSourceIds
import tachiyomi.i18n.sy.SYMR

/**
 * The parts of the Android app's `exh.util.SourceTagsUtil` and `MetadataUIUtil` the metadata
 * views use: tag search strings, genre colors and labels, and rating labels. The Android files
 * also hold Android views and Android-only Compose code, so they are not shared.
 */
object SourceTags {
    private val spaceRegex = "\\s".toRegex()

    /** The search a tag chip runs in its source, as Android's SourceTagsUtil.getWrappedTag. */
    fun getWrappedTag(sourceId: Long?, namespace: String? = null, tag: String? = null, fullTag: String? = null): String? {
        val supported = sourceId in eHentaiSourceIds ||
            sourceId in nHentaiSourceIds ||
            sourceId in mangaDexSourceIds ||
            sourceId == PURURIN_SOURCE_ID ||
            sourceId == TSUMINO_SOURCE_ID ||
            sourceId in lanraragiSourceIds
        if (!supported) return null
        val parsed = when {
            fullTag != null -> parseTag(fullTag)
            namespace != null && tag != null -> RaisedTag(namespace, tag, 0)
            else -> null
        }
        val ns = parsed?.namespace ?: return null
        val name = parsed.name.substringBefore('|').trim()
        return when (sourceId) {
            in nHentaiSourceIds -> wrapTagNHentai(ns, name)
            in mangaDexSourceIds -> parsed.name
            PURURIN_SOURCE_ID -> name
            TSUMINO_SOURCE_ID -> wrapTagTsumino(ns, name)
            else -> wrapTag(ns, name)
        }
    }

    private fun wrapTag(namespace: String, tag: String) =
        if (tag.contains(spaceRegex)) "$namespace:\"$tag$\"" else "$namespace:$tag$"

    private fun wrapTagNHentai(namespace: String, tag: String) = if (tag.contains(spaceRegex)) {
        if (namespace == "tag") "\"$tag\"" else "$namespace:\"$tag\""
    } else {
        "$namespace:$tag"
    }

    private fun wrapTagTsumino(namespace: String, tag: String) = if (tag.contains(spaceRegex)) {
        if (namespace == "tags") "\"${tag.replace(spaceRegex, "_")}\"" else "\"$namespace: ${tag.replace(spaceRegex, "_")}\""
    } else {
        if (namespace == "tags") tag else "$namespace:$tag"
    }

    private fun parseTag(tag: String) = RaisedTag(
        (if (tag.startsWith("-")) tag.substringAfter("-") else tag)
            .substringBefore(':', missingDelimiterValue = "").trim().ifEmpty { null },
        tag.substringAfter(':', missingDelimiterValue = tag).trim(),
        if (tag.startsWith("-")) 69 else 0,
    )

    /** The E-Hentai category colors, and the text color that reads on each. */
    enum class GenreColor(val color: Color, val textColor: Color) {
        DOUJINSHI(Color(0xFFFF614D), Color.Black),
        MANGA(Color(0xFFFF9800), Color.Black),
        ARTIST_CG(Color(0xFFFBC02D), Color.Black),
        GAME_CG(Color(0xFF4CAF50), Color.Black),
        WESTERN(Color(0xFF8BC34A), Color.Black),
        NON_H(Color(0xFF2C9BF8), Color.Black),
        IMAGE_SET(Color(0xFF3C4FB3), Color.White),
        COSPLAY(Color(0xFF921AA6), Color.White),
        ASIAN_PORN(Color(0xFFA685DF), Color.Black),
        MISC(Color(0xFFF36594), Color.Black),
    }

    /** A gallery category's color and label, as MetadataUIUtil.getGenreAndColour. */
    fun genre(genre: String): Pair<GenreColor, StringResource>? = when (genre) {
        "doujinshi", "Doujinshi" -> GenreColor.DOUJINSHI to SYMR.strings.doujinshi
        "manga", "Japanese Manga", "Manga" -> GenreColor.MANGA to SYMR.strings.entry_type_manga
        "artistcg", "artist CG", "artist-cg", "Artist CG" -> GenreColor.ARTIST_CG to SYMR.strings.artist_cg
        "gamecg", "game CG", "game-cg", "Game CG" -> GenreColor.GAME_CG to SYMR.strings.game_cg
        "western" -> GenreColor.WESTERN to SYMR.strings.western
        "non-h", "non-H" -> GenreColor.NON_H to SYMR.strings.non_h
        "imageset", "image Set" -> GenreColor.IMAGE_SET to SYMR.strings.image_set
        "cosplay" -> GenreColor.COSPLAY to SYMR.strings.cosplay
        "asianporn", "asian Porn" -> GenreColor.ASIAN_PORN to SYMR.strings.asian_porn
        "misc" -> GenreColor.MISC to SYMR.strings.misc
        "Korean Manhwa" -> GenreColor.ARTIST_CG to SYMR.strings.entry_type_manhwa
        "Chinese Manhua" -> GenreColor.GAME_CG to SYMR.strings.entry_type_manhua
        "Comic" -> GenreColor.WESTERN to SYMR.strings.entry_type_comic
        "artbook" -> GenreColor.IMAGE_SET to SYMR.strings.artbook
        "webtoon" -> GenreColor.NON_H to SYMR.strings.entry_type_webtoon
        "Video" -> GenreColor.WESTERN to SYMR.strings.video
        else -> null
    }

    /** The label of a rating out of 10, as MetadataUIUtil.getRatingString. */
    fun rating(rating: Float?): StringResource = when (rating?.let { Math.round(it) }) {
        0 -> SYMR.strings.rating0
        1 -> SYMR.strings.rating1
        2 -> SYMR.strings.rating2
        3 -> SYMR.strings.rating3
        4 -> SYMR.strings.rating4
        5 -> SYMR.strings.rating5
        6 -> SYMR.strings.rating6
        7 -> SYMR.strings.rating7
        8 -> SYMR.strings.rating8
        9 -> SYMR.strings.rating9
        10 -> SYMR.strings.rating10
        else -> SYMR.strings.no_rating
    }

    /**
     * The flag emoji before an E-Hentai language name, as SourceTagsUtil.getLocaleSourceUtil and
     * FlagEmoji.getEmojiLangFlag: the language's country, or a globe for other languages.
     */
    fun languageFlag(language: String): String {
        val country = when (language.lowercase()) {
            "english", "eng" -> "US"
            "japanese" -> "JP"
            "chinese" -> "CN"
            "spanish" -> "ES"
            "korean" -> "KR"
            "russian" -> "RU"
            "french" -> "FR"
            "portuguese" -> "PT"
            "thai" -> "TH"
            "german" -> "DE"
            "italian" -> "IT"
            "vietnamese" -> "VN"
            "polish" -> "PL"
            "hungarian" -> "HU"
            "dutch" -> "NL"
            else -> return "\uD83C\uDF0E"
        }
        return country.map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
    }
}
