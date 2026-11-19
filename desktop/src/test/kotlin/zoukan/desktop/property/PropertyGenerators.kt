package zoukan.desktop.property

import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Sink
import okio.buffer
import okio.gzip
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * How the property tests run: a fixed seed, so a run is repeatable and a failure on one machine
 * fails the same way on another, and a bounded number of tries, so they stay in the fast unit tier.
 *
 * To repeat a run with another seed, or to search longer by hand:
 *
 *     ./gradlew :desktop:test --tests "zoukan.desktop.property.*" -Pzoukan.property.seed=42 -Pzoukan.property.iterations=20000
 *
 * Kotest names the seed in a failure's message.
 */
object PropertyRuns {
    const val DEFAULT_SEED = 20_261_118L

    val seed: Long = System.getProperty("zoukan.property.seed")?.toLongOrNull() ?: DEFAULT_SEED

    private val iterationsOverride: Int? = System.getProperty("zoukan.property.iterations")?.toIntOrNull()

    /** [iterations] tries with the run's seed, unless `zoukan.property.iterations` asks for more or fewer. */
    fun config(iterations: Int): PropTestConfig = PropTestConfig(seed = seed, iterations = iterationsOverride ?: iterations)
}

/** An [Arb] from a plain [Random]: the generators here pick from lists of known bad cases and random text. */
fun <T> arbFrom(generate: (Random) -> T): Arb<T> = arbitrary { rs -> generate(rs.random) }

/** [s] with every character that is not printable ASCII written as `\uXXXX`, for failure messages. */
fun escaped(s: String): String = buildString {
    s.forEach { c -> if (c in ' '..'~' && c != '\\') append(c) else append("\\u%04x".format(c.code)) }
}

/** The shortest of each kind of failure, so a report shows the simplest input that fails. */
class Counterexamples<K : Any> {
    private val shortest = linkedMapOf<K, String>()

    fun add(kind: K, input: String, detail: String) {
        val text = "input \"${escaped(input)}\" (${input.length} chars): $detail"
        val old = shortest[kind]
        if (old == null || text.length < old.length) shortest[kind] = text
    }

    fun kinds(): Set<K> = shortest.keys

    fun report(kinds: Collection<K> = shortest.keys): String =
        kinds.filter { it in shortest }.joinToString("\n") { "$it: ${shortest[it]}" }
}

/**
 * Text a source, a user or a damaged file may hand the app: titles, chapter names, scanlators,
 * preference keys and values. Built from pieces known to break file names and parsers, mixed with
 * random characters, and sometimes padded to just around the 240-byte name limit with characters
 * of 1 to 4 UTF-8 bytes, so a truncation can fall inside a character.
 */
object NastyStrings {

    /** Pieces that are each a known hard case for a file name. */
    val pieces: List<String> = listOf(
        "", " ", "  ", ".", "..", "...", " .", ". ", "a.", "a ",
        // Windows device names, in any case and with an extension
        "CON", "con", "Con", "PRN", "AUX", "NUL", "nul", "COM1", "com9", "LPT1", "lpt9", "COM\u00b9", "CON.txt", "aux.tar.gz", "NUL ",
        // Reserved on Windows, and the separators
        "<", ">", ":", "\"", "/", "\\", "|", "?", "*", "../", "..\\", "/etc/passwd", "C:\\", "\\\\?\\",
        // Control characters and odd spaces
        "\u0000", "\u0001", "\t", "\n", "\r", "\u001f", "\u007f", "\u0085", "\u00a0", "\u2028", "\u3000", "\ufeff", "\u200b", "\u200d",
        // Emoji, flags, ZWJ sequences, a mathematical letter (all surrogate pairs)
        "\ud83d\ude00", "\ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67", "\ud83c\uddef\ud83c\uddf5", "\ud835\udd4f",
        // Combining characters and strings that differ only by Unicode normalisation or case
        "e\u0301", "\u0301", "\u00e9", "\u00c5", "\u212b", "A\u030a", "\u00df", "\u1e9e", "\u0130", "\u0131", "\ufb01", "\u01c4",
        // Right-to-left text and direction marks
        "\u202e", "\u200f", "\u05e2\u05d1\u05e8\u05d9\u05ea", "\u0627\u0644\u0639\u0631\u0628\u064a\u0629",
        // CJK and fullwidth look-alikes of reserved characters
        "\u6f2b\u753b", "\u307e\u3093\u304c", "\ud55c\uad6d\uc5b4", "\uff1a", "\uff0f", "\uff3c", "\uff1f",
        // Ordinary chapter-name shapes
        "Ch. 1", "Vol.2 Ch.3", "Chapter 10.5", "%2e%2e", "~", "\$HOME", "a", "Z", "0", "_",
    )

    /** Lone surrogates: a Kotlin String may hold them, UTF-8 cannot. */
    val loneSurrogates: List<String> = listOf("\ud83d", "\ude00", "\udbff", "\ud800")

    /** One character each of 1, 2, 3 and 4 UTF-8 bytes, and the two characters Windows trims. */
    private val paddingUnits = listOf("a", "\u00e9", "\u6f2b", "\ud83d\ude00", " ", ".")

    /**
     * A random string. [allowLoneSurrogates] false keeps it valid Unicode, for data that only ever
     * comes from UTF-8 (the database, XML, protobuf).
     */
    fun next(random: Random, allowLoneSurrogates: Boolean = true, maxPieces: Int = 10, padding: Boolean = true): String {
        val sb = StringBuilder()
        repeat(random.nextInt(0, maxPieces + 1)) {
            sb.append(
                when (random.nextInt(10)) {
                    0, 1, 2, 3 -> pieces.random(random)
                    4 -> randomAscii(random)
                    5 -> randomBmp(random, allowLoneSurrogates)
                    6 -> String(Character.toChars(random.nextInt(0x10000, 0x110000)))
                    7 -> if (allowLoneSurrogates) loneSurrogates.random(random) else pieces.random(random)
                    else -> randomWord(random)
                },
            )
        }
        if (padding && random.nextInt(5) == 0) {
            // Around the limit: 200 to 260 bytes of one unit, before or after the rest
            val unit = paddingUnits.random(random)
            val bytes = unit.toByteArray(Charsets.UTF_8).size
            val pad = unit.repeat(random.nextInt(200, 261) / bytes)
            if (random.nextBoolean()) sb.insert(0, pad) else sb.append(pad)
            if (random.nextBoolean()) sb.append(pieces.random(random))
        }
        return sb.toString()
    }

    private fun randomAscii(random: Random): String = random.nextInt(0, 0x80).toChar().toString()

    private fun randomBmp(random: Random, allowLoneSurrogates: Boolean): String {
        val c = random.nextInt(0, 0x10000).toChar()
        return if (!allowLoneSurrogates && c.isSurrogate()) "x" else c.toString()
    }

    private fun randomWord(random: Random): String =
        (1..random.nextInt(1, 12)).map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_.".random(random) }
            .joinToString("")
}

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

/** An HTTP client that never touches the network: [answer] gives each request's body, or null for a 404. */
class FakeHttp(private val answer: (request: Request, count: Int) -> ByteArray?) {
    val requests = AtomicInteger()

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val body = answer(chain.request(), requests.incrementAndGet())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(if (body == null) 404 else 200)
                .message(if (body == null) "Not Found" else "OK")
                .body((body ?: ByteArray(0)).toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }
        .build()

    val network: NetworkHelper = mockk { every { client } returns this@FakeHttp.client }
}

/** Valid documents of each outside format, to break in [Mutations]. */
object ValidDocuments {
    val comicInfo: String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ComicInfo xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <Title>Chapter 12.5</Title>
          <Series>A Title &amp; More</Series>
          <Number>12.5</Number>
          <Summary>Line one
        line two</Summary>
          <Writer>Someone</Writer>
          <Penciller>A, B</Penciller>
          <Genre>Action, Drama</Genre>
          <Translator>Group</Translator>
          <ty:PublishingStatusTachiyomi xmlns:ty="http://www.w3.org/2001/XMLSchema">Ongoing</ty:PublishingStatusTachiyomi>
        </ComicInfo>
    """.trimIndent()

    val storeIndex: String = """
        {"name":"Store","badgeLabel":"ST","signingKey":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
         "contact":{"website":"https://store.test","discord":null},
         "extensionList":{"extensions":[{"name":"Tachiyomi: Example","packageName":"eu.kanade.tachiyomi.extension.en.example",
           "resources":{"apkUrl":"https://store.test/apk/example.apk","iconUrl":"https://store.test/icon/example.png","jarUrl":"https://store.test/jar/example.jar"},
           "extensionLib":"1.4","versionCode":12,"versionName":"1.4.12","contentWarning":"CONTENT_WARNING_SAFE",
           "sources":[{"id":1234567890123,"name":"Example","language":"en","homeUrl":"https://example.test","mirrorUrls":["https://m.example.test"]}]}]},
         "extensionListUrl":null}
    """.trimIndent()

    val legacyRepo: String = """
        {"meta":{"name":"Store","shortName":"ST","website":"https://store.test","signingKeyFingerprint":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}}
    """.trimIndent()

    val legacyIndex: String = """
        [{"name":"Tachiyomi: Example","pkg":"eu.kanade.tachiyomi.extension.en.example","apk":"example-v1.4.12.apk","lang":"en","code":12,
          "version":"1.4.12","nsfw":0,"sources":[{"id":1234567890123,"lang":"en","name":"Example","baseUrl":"https://example.test"}]}]
    """.trimIndent()

    val releases: String = """
        [{"tag_name":"desktop-v9.9.9","name":"Zoukan 9.9.9","body":"Notes","draft":false,"prerelease":false,
          "html_url":"https://github.test/o/r/releases/tag/desktop-v9.9.9",
          "assets":[{"name":"Zoukan-9.9.9.msi","url":"https://api.github.com/repos/o/r/releases/assets/1","size":123,"digest":"sha256:00ff"},
                    {"name":"Zoukan-9.9.9.msi.sig","url":"https://api.github.com/repos/o/r/releases/assets/2","size":64},
                    {"name":"Zoukan-9.9.9-arm64.dmg","url":"https://api.github.com/repos/o/r/releases/assets/3","size":456}]},
         {"tag_name":"desktop-v1.0","draft":true,"assets":[]}]
    """.trimIndent()

    val release: String = """
        {"tag_name":"desktop-v9.9.9","name":"Zoukan 9.9.9","body":"Notes","html_url":"https://github.test/o/r/releases/tag/desktop-v9.9.9","assets":[]}
    """.trimIndent()
}
