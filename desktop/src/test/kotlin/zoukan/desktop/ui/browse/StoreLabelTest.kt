package zoukan.desktop.ui.browse

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test

class StoreLabelTest {

    private val store = ExtensionStore(
        indexUrl = "https://store.example/index.min.json",
        name = "Zoukan Fixture Store",
        badgeLabel = "ZFS",
        signingKey = "key",
        contact = ExtensionStore.Contact(website = "https://store.example", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private val installed: Extension = mockk<Extension.Installed> {
        every { signatureHash } returns "key"
        every { storeName } returns "ZFS"
    }

    @Test
    fun `an installed extension shows its store's full name`() {
        storeLabel(installed, listOf(store)) shouldBe "Zoukan Fixture Store"
    }

    @Test
    fun `an extension whose store is gone keeps its short name`() {
        storeLabel(installed, emptyList()) shouldBe "ZFS"
    }

    @Test
    fun `an extension signed with another key keeps its own name`() {
        storeLabel(installed, listOf(store.copy(signingKey = "other"))) shouldBe "ZFS"
    }
}
