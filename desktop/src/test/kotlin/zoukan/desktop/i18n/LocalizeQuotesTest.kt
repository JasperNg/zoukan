package zoukan.desktop.i18n

import dev.icerock.moko.resources.StringResource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.action_global_search_query
import tachiyomi.i18n.delete_category_confirmation
import tachiyomi.i18n.extensionStoresScreen_deleteStore_body
import tachiyomi.i18n.pref_relative_format_summary
import tachiyomi.i18n.pref_update_only_non_completed
import tachiyomi.i18n.remove_manga
import tachiyomi.i18n.remove_private_extension_message
import zoukan.desktop.compat.localized

/** Shared strings written with escaped quotes must show plain quotes, never a backslash, on desktop. */
class LocalizeQuotesTest {

    // The desktop actual does not use its receiver
    private val context = mockk<android.content.Context>(relaxed = true)

    @Test
    fun `shared strings with quotes show no backslash on desktop`() {
        context.stringResource(MR.strings.delete_category_confirmation, "X") shouldBe "Do you wish to delete the category \"X\"?"
        context.stringResource(MR.strings.remove_manga, "X") shouldBe "You are about to remove \"X\" from your library"
    }

    @Test
    fun `desktop ui strings with quotes show no backslash`() {
        val text = localized(MR.strings.delete_category_confirmation, "X")
        text shouldBe "Do you wish to delete the category \"X\"?"
        text shouldNotContain "\\"
    }

    // Every base string written with \" and how many arguments it takes
    private val quoted: List<Pair<StringResource, Int>> = listOf(
        MR.strings.delete_category_confirmation to 1,
        MR.strings.pref_relative_format_summary to 2,
        MR.strings.pref_update_only_non_completed to 0,
        MR.strings.remove_private_extension_message to 1,
        MR.strings.extensionStoresScreen_deleteStore_body to 2,
        MR.strings.action_global_search_query to 1,
        MR.strings.remove_manga to 1,
    )

    @Test
    fun `every shared string with quotes shows no backslash through both lookups`() {
        quoted.forEach { (resource, argCount) ->
            val args = Array<Any>(argCount) { "X" }
            val shared = if (argCount == 0) context.stringResource(resource) else context.stringResource(resource, *args)
            val desktop = if (argCount == 0) localized(resource) else localized(resource, *args)
            shared shouldNotContain "\\"
            desktop shouldNotContain "\\"
        }
    }
}
