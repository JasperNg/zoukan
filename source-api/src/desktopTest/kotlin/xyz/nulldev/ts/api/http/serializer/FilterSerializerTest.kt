package xyz.nulldev.ts.api.http.serializer

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Saved searches keep their filters as this serializer's JSON, in both apps' databases and in
 * backups. A saved search must come back with every filter as it was, and one saved against an
 * older version of the source's filters must not fail to open.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FilterSerializerTest {

    private val serializer = FilterSerializer()

    private class Header : Filter.Header("Note")
    private class Separator : Filter.Separator()
    private class Select : Filter.Select<String>("Type", arrayOf("Any", "Manga", "Novel"))
    private class Text : Filter.Text("Author")
    private class Check(name: String) : Filter.CheckBox(name)
    private class Tri(name: String) : Filter.TriState(name)
    private class Genres : Filter.Group<Filter<*>>("Genres", listOf(Tri("Action"), Tri("Drama"), Check("Completed")))
    private class Sort : Filter.Sort("Order", arrayOf("Latest", "Popular"), Selection(0, true))
    private class Tags : Filter.AutoComplete("Tags", "hint", listOf("a", "b"), state = emptyList())

    private fun filters() = FilterList(Header(), Separator(), Select(), Text(), Check("Only new"), Genres(), Sort(), Tags())

    private fun FilterList.states(): List<Any?> = map { filter ->
        when (filter) {
            is Filter.Group<*> -> filter.state.map { (it as Filter<*>).state }
            else -> filter.state
        }
    }

    private fun FilterList.edit() = apply {
        (this[2] as Select).state = 2
        (this[3] as Text).state = "Ōta \"Jr\", 小説\n"
        (this[4] as Check).state = true
        @Suppress("UNCHECKED_CAST")
        val genres = (this[5] as Genres).state as List<Filter<Any?>>
        genres[0].state = Filter.TriState.STATE_INCLUDE
        genres[1].state = Filter.TriState.STATE_EXCLUDE
        genres[2].state = true
        (this[6] as Sort).state = Filter.Sort.Selection(1, false)
        (this[7] as Tags).state = listOf("female:glasses", "-male:beard")
    }

    @Test
    fun `every kind of filter comes back as it was saved, through stored JSON text`() {
        val edited = filters().edit()
        val stored = Json.encodeToString(JsonArray.serializer(), serializer.serialize(edited))

        val restored = filters()
        serializer.deserialize(restored, Json.decodeFromString(JsonArray.serializer(), stored))
        restored.states() shouldBe edited.states()
    }

    @Test
    fun `a sort with no selection stays unselected`() {
        val edited = filters().apply { (this[6] as Sort).state = null }
        val restored = filters()
        serializer.deserialize(restored, serializer.serialize(edited))
        (restored[6] as Sort).state shouldBe null
    }

    @Test
    fun `a search saved against older filters restores what still matches and skips the rest`() {
        // The source has since put a new checkbox where the select was
        val saved = serializer.serialize(FilterList(Select().apply { state = 1 }, Text().apply { state = "kept" }))
        val now = FilterList(Check("New option"), Text())
        serializer.deserialize(now, saved)
        (now[0] as Check).state shouldBe false
        (now[1] as Text).state shouldBe "kept"
    }

    @Test
    fun `more or fewer saved filters than the source has now is not an error`() {
        val saved = serializer.serialize(FilterList(Text().apply { state = "one" }))
        val longer = FilterList(Text(), Check("Extra"))
        serializer.deserialize(longer, saved)
        (longer[0] as Text).state shouldBe "one"
        (longer[1] as Check).state shouldBe false

        val shorter = FilterList()
        serializer.deserialize(shorter, serializer.serialize(filters().edit()))
        shorter.isEmpty() shouldBe true
    }

    @Test
    fun `a damaged saved filter is skipped and does not stop the others`() {
        val saved = buildJsonArray {
            add(buildJsonObject { put("_type", "UNKNOWN_TYPE") })
            add(buildJsonObject { put("_type", "TEXT") })
            add(serializer.serialize(FilterList(Check("x").apply { state = true })).single())
        }
        val now = FilterList(Text().apply { state = "unchanged" }, Text().apply { state = "also" }, Check("x"))
        serializer.deserialize(now, saved)
        (now[0] as Text).state shouldBe "unchanged"
        (now[1] as Text).state shouldBe "also"
        (now[2] as Check).state shouldBe true
    }
}
