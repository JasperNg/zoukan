package zoukan.desktop.property

import okio.Buffer
import okio.Sink
import okio.buffer
import okio.gzip
import kotlin.random.Random

/**
 * Inputs for parsers of outside data: random bytes, random text, and a valid document broken in
 * the ways a server, a disk or an attacker breaks one: cut off, chunks deleted, repeated or
 * swapped, values of another type, huge numbers, deep nesting, odd bytes, sometimes gzipped.
 */
object Mutations {

    enum class Format { JSON, XML, BINARY }

    fun input(random: Random, valid: List<ByteArray>, format: Format): ByteArray = when (random.nextInt(10)) {
        0 -> random.nextBytes(random.nextInt(0, 64))
        1 -> NastyStrings.next(random).toByteArray(Charsets.UTF_8)
        2 -> valid.random(random)
        else -> {
            var doc = valid.random(random)
            repeat(random.nextInt(1, 4)) { doc = mutate(random, doc, format) }
            if (random.nextInt(8) == 0) gzip(doc) else doc
        }
    }

    fun mutate(r: Random, doc: ByteArray, format: Format): ByteArray {
        if (doc.isEmpty()) return r.nextBytes(r.nextInt(1, 8))
        val text = doc.toString(Charsets.ISO_8859_1)
        fun pos() = r.nextInt(0, doc.size + 1)
        fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)
        return when (r.nextInt(10)) {
            // Cut off
            0 -> doc.copyOf(pos())
            // A chunk deleted
            1 -> {
                val a = pos()
                val b = (a + r.nextInt(1, 32)).coerceAtMost(doc.size)
                doc.copyOfRange(0, a) + doc.copyOfRange(b, doc.size)
            }
            // A chunk repeated
            2 -> {
                val a = pos()
                val b = (a + r.nextInt(1, 64)).coerceAtMost(doc.size)
                doc.copyOfRange(0, b) + doc.copyOfRange(a, b) + doc.copyOfRange(b, doc.size)
            }
            // Two chunks swapped
            3 -> {
                val cuts = List(3) { pos() }.sorted()
                doc.copyOfRange(0, cuts[0]) + doc.copyOfRange(cuts[1], cuts[2]) + doc.copyOfRange(cuts[0], cuts[1]) + doc.copyOfRange(cuts[2], doc.size)
            }
            // Odd text put in
            4 -> {
                val p = pos()
                doc.copyOfRange(0, p) + bytes(NastyStrings.next(r, maxPieces = 3)) + doc.copyOfRange(p, doc.size)
            }
            // A number made huge, negative or not a number
            5 -> {
                val number = Regex("""-?\d+(\.\d+)?""").findAll(text).toList().randomOrNull(r)
                    ?: return mutate(r, doc, format)
                val huge = listOf("99999999999999999999999999999", "-9223372036854775809", "1e309", "-1", "NaN", "0x7fffffff", "1".repeat(5000)).random(r)
                doc.copyOfRange(0, number.range.first) + bytes(huge) + doc.copyOfRange(number.range.last + 1, doc.size)
            }
            // A value of another type
            6 -> when (format) {
                Format.JSON -> {
                    val value = Regex("\"[^\"]*\"|-?\\d+|true|false|null|\\[\\]|\\{\\}").findAll(text).toList().randomOrNull(r)
                        ?: return mutate(r, doc, format)
                    val other = listOf("123", "-1", "true", "null", "[]", "{}", "\"x\"", "[1,2]", "{\"a\":1}", "1.5").random(r)
                    doc.copyOfRange(0, value.range.first) + bytes(other) + doc.copyOfRange(value.range.last + 1, doc.size)
                }
                Format.XML -> {
                    val value = Regex(">[^<]+<").findAll(text).toList().randomOrNull(r) ?: return mutate(r, doc, format)
                    val other = listOf("><a><b/></a><", "><![CDATA[x]]><", "><!-- c --><", ">&amp;&lt;&#0;<", ">&#x110000;<", ">&undefined;<", "><?pi x?><").random(r)
                    doc.copyOfRange(0, value.range.first) + bytes(other) + doc.copyOfRange(value.range.last + 1, doc.size)
                }
                Format.BINARY -> doc.copyOf().also { it[r.nextInt(it.size)] = listOf(0x08, 0x0a, 0x12, 0x1a, 0xff, 0x80, 0x7f).random(r).toByte() }
            }
            // Deep nesting
            7 -> {
                val depth = r.nextInt(100, 20_000)
                when (format) {
                    Format.JSON -> bytes("{\"a\":".repeat(depth / 4) + "[".repeat(depth) + "]".repeat(depth) + "}".repeat(depth / 4))
                    Format.XML -> bytes("<ComicInfo>" + "<a>".repeat(depth) + "x" + "</a>".repeat(depth) + "</ComicInfo>")
                    // A length-delimited field whose length is far past the end, then nested ones
                    Format.BINARY -> byteArrayOf(0x0a, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x07) +
                        ByteArray(depth.coerceAtMost(2_000)) { if (it % 2 == 0) 0x0a else 0x7f }
                }
            }
            // Random bytes flipped
            8 -> doc.copyOf().also { copy -> repeat(r.nextInt(1, 6)) { copy[r.nextInt(copy.size)] = r.nextInt(256).toByte() } }
            // XML entities and DOCTYPEs, a byte order mark, another encoding declared
            else -> {
                val prefix = listOf(
                    "﻿",
                    "<?xml version=\"1.0\" encoding=\"UTF-16\"?>",
                    "<!DOCTYPE ComicInfo [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">]>",
                    "<!DOCTYPE ComicInfo SYSTEM \"http://127.0.0.1:9/x.dtd\">",
                    "<!DOCTYPE ComicInfo [<!ENTITY % p SYSTEM \"file:///nonexistent\"> %p;]>",
                    "\u0000",
                ).random(r)
                bytes(prefix) + doc
            }
        }
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val out = Buffer()
        (out as Sink).gzip().buffer().use { it.write(bytes) }
        return out.readByteArray()
    }
}
