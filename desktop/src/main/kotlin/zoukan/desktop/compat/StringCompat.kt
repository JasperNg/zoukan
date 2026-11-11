// ZOUKAN -->
@file:JvmName("StringCompat")

package eu.kanade.tachiyomi.util.lang

import org.jsoup.parser.Parser
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** HTML-decodes the string, as Android's parseAsHtml().toString() does for entities. */
fun String.htmlDecode(): String = Parser.unescapeEntities(this, false)

/** From the Android app's DateExtensions. */
fun Long.convertEpochMillisZone(from: ZoneId, to: ZoneId): Long =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(this), from).atZone(to).toInstant().toEpochMilli()
// ZOUKAN <--
