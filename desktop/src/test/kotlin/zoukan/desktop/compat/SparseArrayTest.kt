package zoukan.desktop.compat

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SparseArrayTest {

    @Test
    fun `keeps keys in order like Android`() {
        val array = SparseArray<String>()
        array.put(5, "five")
        array.put(1, "one")
        array.put(5, "FIVE")
        array.size() shouldBe 2
        array.keyAt(0) shouldBe 1
        array.valueAt(1) shouldBe "FIVE"
        array.get(2) shouldBe null
        val seen = mutableListOf<Int>()
        array.forEach { key, _ -> seen += key }
        seen shouldBe listOf(1, 5)
    }
}
