package zoukan.desktop.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import zoukan.desktop.ui.InlineDialogScanner.Site

class InlineDialogScannerTest {
    private fun sites(source: String) = InlineDialogScanner.sites(source.trimIndent())

    @Test
    fun `a dialog after a local fun that has closed belongs to the outer fun`() {
        sites(
            """
            @Composable
            fun SettingsTab() {
                var open by remember { mutableStateOf(false) }
                @Composable
                fun SearchResults(query: String) {
                    Text(query)
                }
                SearchResults("x")
                if (open) {
                    AlertDialog(onDismissRequest = {})
                }
            }
            """,
        ) shouldBe listOf(Site(10, "SettingsTab"))
    }

    @Test
    fun `a dialog in a nested fun belongs to the innermost open fun`() {
        sites(
            """
            fun Outer() {
                fun Middle() {
                    fun Inner() {
                        Dialog(onDismissRequest = {}) {}
                    }
                    AlertDialog(onDismissRequest = {})
                }
                Box { AlertDialog(onDismissRequest = {}) }
            }
            AlertDialog(onDismissRequest = {})
            """,
        ) shouldBe listOf(Site(4, "Inner"), Site(6, "Middle"), Site(8, "Outer"), Site(10, null))
    }

    @Test
    fun `a dialog in an override fun Content of a screen belongs to Content`() {
        sites(
            """
            object CategoryScreen : Screen {
                override val key = "category"

                @Composable
                override fun Content() {
                    val model = rememberScreenModel { CategoryModel() }
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Delete?") },
                    )
                }
            }
            """,
        ) shouldBe listOf(Site(7, "Content"))
    }

    @Test
    fun `a dialog on the fun line itself belongs to that fun, with a block or an expression body`() {
        sites(
            """
            fun Block() { AlertDialog(onDismissRequest = {}) }
            @Composable private fun Expression() = AlertDialog(onDismissRequest = {})
            fun After() {
                Dialog(onDismissRequest = {}) {}
            }
            """,
        ) shouldBe listOf(Site(1, "Block"), Site(2, "Expression"), Site(4, "After"))
    }

    @Test
    fun `braces in strings, comments and default arguments do not count`() {
        sites(
            """
            fun First(onClick: () -> Unit = {}) {
                Text("}}} not code {")
                Text(${"\"\"\""}
                    }
                ${"\"\"\""})
                val c = '}'
                // }
                /* } */
                Text("${'$'}{if (a) "}" else "{"} AlertDialog(")
            }
            fun Second() {
                AlertDialog(onDismissRequest = {})
            }
            """,
        ) shouldBe listOf(Site(12, "Second"))
    }

    @Test
    fun `a call in a comment or a string is not a site`() {
        sites(
            """
            fun Panel() {
                // AlertDialog(onDismissRequest = {})
                Text("Dialog(")
            }
            """,
        ) shouldBe emptyList()
    }

    @Test
    fun `a fun without a body ends before the next fun`() {
        sites(
            """
            interface Shows {
                fun show()
            }
            fun Later() {
                AlertDialog(onDismissRequest = {})
            }
            """,
        ) shouldBe listOf(Site(5, "Later"))
    }
}
