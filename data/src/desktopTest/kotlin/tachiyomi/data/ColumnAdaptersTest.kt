package tachiyomi.data

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import mihon.core.common.extensions.JsonObjectEmptyBytes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.Date

/**
 * The column adapters both apps read and write the database with: a value written by one app must
 * come back the same in the other, and an odd stored value must not crash a read.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ColumnAdaptersTest {

    @Test
    fun `dates keep their milliseconds, the epoch and dates before it`() {
        listOf(0L, 1L, -86_400_000L, 1_700_000_000_123L, Long.MAX_VALUE).forEach { millis ->
            val stored = DateColumnAdapter.encode(Date(millis))
            stored shouldBe millis
            DateColumnAdapter.decode(stored).time shouldBe millis
        }
    }

    @Test
    fun `genre lists round trip with the separator the Android app uses`() {
        val genres = listOf("Action", "Slice of Life", "Sci-Fi", "日常", "")
        StringListColumnAdapter.encode(listOf("Action", "Drama")) shouldBe "Action, Drama"
        StringListColumnAdapter.decode(StringListColumnAdapter.encode(genres.dropLast(1))) shouldBe genres.dropLast(1)
        StringListColumnAdapter.decode("Action") shouldBe listOf("Action")
    }

    @Test
    fun `an empty genre list is stored as an empty string and read back empty`() {
        StringListColumnAdapter.encode(emptyList()) shouldBe ""
        StringListColumnAdapter.decode("") shouldBe emptyList()
    }

    @Test
    fun `genre values that break the round trip are read the way the Android app reads them`() {
        // A genre containing the separator splits, and a list of only an empty genre comes back
        // empty: the format is shared with the Android app, so both apps agree on these
        StringListColumnAdapter.decode(StringListColumnAdapter.encode(listOf("Boys, Love"))) shouldBe listOf("Boys", "Love")
        StringListColumnAdapter.decode(StringListColumnAdapter.encode(listOf(""))) shouldBe emptyList()
        // A comma without a space is not a separator
        StringListColumnAdapter.decode("Action,Drama") shouldBe listOf("Action,Drama")
    }

    @Test
    fun `every update strategy round trips by its ordinal`() {
        UpdateStrategy.entries.forEach { strategy ->
            UpdateStrategyColumnAdapter.decode(UpdateStrategyColumnAdapter.encode(strategy)) shouldBe strategy
        }
        UpdateStrategyColumnAdapter.encode(UpdateStrategy.ALWAYS_UPDATE) shouldBe 0L
        UpdateStrategyColumnAdapter.encode(UpdateStrategy.ONLY_FETCH_ONCE) shouldBe 1L
    }

    @Test
    fun `an unknown update strategy reads as always update instead of failing`() {
        UpdateStrategyColumnAdapter.decode(-1L) shouldBe UpdateStrategy.ALWAYS_UPDATE
        UpdateStrategyColumnAdapter.decode(UpdateStrategy.entries.size.toLong()) shouldBe UpdateStrategy.ALWAYS_UPDATE
        UpdateStrategyColumnAdapter.decode(Long.MAX_VALUE) shouldBe UpdateStrategy.ALWAYS_UPDATE
    }

    @Test
    fun `memos round trip, nested values and non-ASCII text included`() {
        val memo = buildJsonObject {
            put("mihon.note", "ノート \"quoted\" \\ back")
            put("count", 3)
            put("nothing", JsonPrimitive(null as String?))
            putJsonArray("list") {
                add(JsonPrimitive(1))
                add(JsonPrimitive("two"))
            }
        }
        MemoColumnAdapter.decode(MemoColumnAdapter.encode(memo)) shouldBe memo
    }

    @Test
    fun `the empty memo is the column's default bytes`() {
        MemoColumnAdapter.decode(JsonObjectEmptyBytes).isEmpty() shouldBe true
        MemoColumnAdapter.encode(buildJsonObject { }).toList() shouldBe JsonObjectEmptyBytes.toList()
    }

    @Test
    fun `a memo that is not a JSON object fails with a serialization error`() {
        shouldThrow<SerializationException> { MemoColumnAdapter.decode("[1]".encodeToByteArray()) }
        shouldThrow<SerializationException> { MemoColumnAdapter.decode("{".encodeToByteArray()) }
    }
}
