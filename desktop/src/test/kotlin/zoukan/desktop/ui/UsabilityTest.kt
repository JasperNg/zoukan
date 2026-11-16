package zoukan.desktop.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The usability rules on small hand-made screens, without the app's runtime. */
@OptIn(ExperimentalTestApi::class)
class UsabilityTest {

    private fun findings(key: String = "screen:test", probe: DismissProbe? = null, content: @Composable () -> Unit): List<UsabilityFinding> {
        var found = emptyList<UsabilityFinding>()
        runDesktopComposeUiTest(width = 800, height = 600) {
            setContent {
                InstallEscapeAsBack()
                content()
            }
            waitForIdle()
            found = usabilityChecks(key, "test", probe)
        }
        return found.onEach(::println)
    }

    private fun List<UsabilityFinding>.of(rule: Rule) = filter { it.rule == rule }

    @Test
    fun `an icon button with no description is an unlabelled clickable`() {
        val found = findings { IconButton(onClick = {}) { Icon(Icons.Outlined.Close, contentDescription = null) } }.of(Rule.UNLABELLED_CLICKABLE)
        found shouldHaveSize 1
        found.single().node.startsWith("@") shouldBe true
    }

    @Test
    fun `an icon button with a description is labelled`() {
        findings { IconButton(onClick = {}) { Icon(Icons.Outlined.Close, contentDescription = "Search") } }
            .of(Rule.UNLABELLED_CLICKABLE).shouldBeEmpty()
    }

    @Test
    fun `a button label cut off with an ellipsis is text overflow`() {
        val found = findings {
            Button(onClick = {}, modifier = Modifier.width(60.dp)) {
                Text("A label far too long for its button", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }.of(Rule.TEXT_OVERFLOW)
        found shouldHaveSize 1
        found.single().node shouldBe "\"A label far too long for its button\""
    }

    @Test
    fun `long text outside a control is not text overflow`() {
        findings {
            Text("A long title that is cut off on purpose, as titles are", Modifier.width(60.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }.of(Rule.TEXT_OVERFLOW).shouldBeEmpty()
    }

    @Test
    fun `labels that fit are not text overflow`() {
        findings("dialog:test.Plain", DismissProbe()) { Dialog({}, DialogProperties()) }.of(Rule.TEXT_OVERFLOW).shouldBeEmpty()
    }

    @Test
    fun `focus moves through a dialog's buttons`() {
        findings("dialog:test.Plain", DismissProbe()) { Dialog({}, DialogProperties()) }.of(Rule.TAB_TRAVERSAL).shouldBeEmpty()
    }

    @Test
    fun `Escape calls a dialog's dismiss`() {
        val probe = DismissProbe()
        findings("dialog:test.Plain", probe) { Dialog(probe.dismiss, DialogProperties()) }
            .of(Rule.ESCAPE_NOT_DISMISSING).shouldBeEmpty()
        probe.called shouldBe true
    }

    @Test
    fun `a dialog that ignores Escape is a finding`() {
        val probe = DismissProbe()
        findings("dialog:test.Blocking", probe) { Dialog(probe.dismiss, DialogProperties(dismissOnBackPress = false)) }
            .of(Rule.ESCAPE_NOT_DISMISSING) shouldHaveSize 1
    }

    @Test
    fun `a dialog that blocks Escape on purpose is not a finding`() {
        val probe = DismissProbe()
        findings("dialog:recs.RecommendationSearchProgressDialog", probe) { Dialog(probe.dismiss, DialogProperties(dismissOnBackPress = false)) }
            .of(Rule.ESCAPE_NOT_DISMISSING).shouldBeEmpty()
    }

    @Composable
    private fun Dialog(onDismiss: () -> Unit, properties: DialogProperties) = AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Smoke") },
        text = { Text("A dialog") },
        confirmButton = { TextButton(onClick = {}) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        properties = properties,
    )
}
