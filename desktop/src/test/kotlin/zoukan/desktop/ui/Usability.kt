@file:OptIn(ExperimentalTestApi::class)

package zoukan.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.InternalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import java.io.File
import kotlin.math.roundToInt

/**
 * One usability problem on a screen or dialog. [key] is the check's coverage-matrix key, [node]
 * a stable name for the node (a test tag, a nearby label or its position, never a semantics id),
 * so that an allowlist can match a finding from one run to the next.
 */
data class UsabilityFinding(val variant: String, val key: String, val rule: Rule, val node: String, val detail: String)

enum class Rule { UNLABELLED_CLICKABLE, TEXT_OVERFLOW, ESCAPE_NOT_DISMISSING, TAB_TRAVERSAL }

/** A dialog's dismiss callback that remembers it was called: the Escape check asserts it. */
class DismissProbe {
    @Volatile
    var called = false
    val dismiss: () -> Unit = { called = true }
}

/** The node of a finding that says a rule threw: never allowlisted (see [classify]), so an error always shows as new. */
const val RULE_ERROR_NODE = "(rule error)"

/** Tab presses before Tab traversal gives up: a dialog or settings page has far fewer controls. */
private const val MAX_TABS = 30

/**
 * Dialog keys whose dialog blocks dismissal on purpose, so Escape must not dismiss it: the
 * recommendation search's progress (dismissOnBackPress = false). For these a dismiss is the finding.
 * The Escape rule runs only for "dialog:" keys, so only those belong here.
 */
val ESCAPE_BLOCKED_ON_PURPOSE = setOf(
    "dialog:recs.RecommendationSearchProgressDialog",
)

/** The roles whose text must fit: a clipped or cut-off label there hides what the control does. */
private val CONTROL_ROLES = setOf(Role.Button, Role.Tab, Role.Checkbox, Role.Switch)

/**
 * Looks at every root on screen (the window, dialogs, popups) for usability problems and returns
 * them; it never throws, and a rule that throws becomes a finding of its own. The rules:
 *
 * - a clickable node with no text, content description or state description, so a screen reader
 *   can't name it;
 * - text in a button, tab, checkbox or switch, or a dialog's title, that is clipped or cut off
 *   with an ellipsis (other ellipses are on purpose, as in long titles, so they are not looked at);
 * - for "dialog:" and "settings:" keys, Tab that gets stuck or doesn't reach every control;
 * - for "dialog:" keys with a [dismiss] probe, Escape that doesn't call the dialog's dismiss. Last,
 *   because it may close the dialog.
 */
fun ComposeUiTest.usabilityChecks(key: String, variant: String, dismiss: DismissProbe?): List<UsabilityFinding> =
    staticUsabilityChecks(key, variant) + interactiveUsabilityChecks(key, variant, dismiss)

/** The rules that only read the screen (labels, overflow): they change nothing, so any number of passes can run. */
fun ComposeUiTest.staticUsabilityChecks(key: String, variant: String): List<UsabilityFinding> = findings(key, variant) { rule ->
    rule(Rule.UNLABELLED_CLICKABLE) { unlabelledClickables() }
    rule(Rule.TEXT_OVERFLOW) { textOverflow() }
}

/** The rules that press keys (Tab, then Escape): they move focus and may close the dialog, so they run last. */
fun ComposeUiTest.interactiveUsabilityChecks(key: String, variant: String, dismiss: DismissProbe?): List<UsabilityFinding> =
    findings(key, variant) { rule ->
        if (key.startsWith("dialog:") || key.startsWith("settings:")) rule(Rule.TAB_TRAVERSAL) { tabTraversal(key) }
        if (key.startsWith("dialog:") && dismiss != null) rule(Rule.ESCAPE_NOT_DISMISSING) { escape(key, dismiss) }
    }

/** Runs the rules [body] names; a rule that throws becomes a finding of its own. */
private fun findings(
    key: String,
    variant: String,
    body: (rule: (Rule, () -> List<Pair<String, String>>) -> Unit) -> Unit,
): List<UsabilityFinding> {
    val findings = mutableListOf<UsabilityFinding>()
    body { rule, check ->
        try {
            check().forEach { (node, detail) -> findings += UsabilityFinding(variant, key, rule, node, detail) }
        } catch (e: Throwable) {
            findings += UsabilityFinding(variant, key, rule, RULE_ERROR_NODE, "rule error: $e")
        }
    }
    return findings
}

// Rules

private fun ComposeUiTest.unlabelledClickables(): List<Pair<String, String>> = roots(merged = true).flatMap { root ->
    root.descendants().filter { node ->
        val config = node.config
        SemanticsActions.OnClick in config &&
            label(node) == null &&
            config.getOrNull(SemanticsProperties.StateDescription).isNullOrBlank() &&
            SemanticsProperties.Disabled !in config &&
            SemanticsProperties.HideFromAccessibility !in config
    }.map { node -> nodeId(node) to "clickable with no text, content description or state description${roleOf(node)}" }
}

private fun ComposeUiTest.textOverflow(): List<Pair<String, String>> {
    val roots = roots(merged = false)
    val dialogs = dialogRoots(roots)
    val titles = dialogs.mapNotNull { root -> root.descendants().firstOrNull { it.text() != null && !it.hasClickableAncestor() } }.toSet()
    return roots.flatMap { it.descendants() }.mapNotNull { node ->
        if (SemanticsProperties.EditableText in node.config) return@mapNotNull null
        val text = node.text() ?: return@mapNotNull null
        val where = node.controlRole()?.let { "in a $it" } ?: if (node in titles) "dialog title" else return@mapNotNull null
        val layout = node.textLayout() ?: return@mapNotNull null
        val last = layout.lineCount - 1
        // isLineEllipsized is not reliable on desktop, but more lines than maxLines always means cut text
        val cut = layout.multiParagraph.didExceedMaxLines || (last >= 0 && layout.isLineEllipsized(last))
        // The text against the node's own laid-out size: the semantics layout result's size and
        // hasVisualOverflow flag labels that fit (its paragraph is as wide as the constraints)
        val wide = last >= 0 && (0..last).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) } - node.size.width > 1f
        val tall = last >= 0 && layout.getLineBottom(last) - node.size.height > 1f
        val node = "\"${short(text)}\""
        when {
            cut && layout.layoutInput.overflow == TextOverflow.Ellipsis -> node to "$where: cut off with an ellipsis"
            cut -> node to "$where: cut off after ${layout.layoutInput.maxLines} lines"
            wide -> node to "$where: clipped, wider than its space"
            tall -> node to "$where: clipped, taller than its space"
            else -> null
        }
    }
}

/**
 * Tab from the first control: focus must move on each press, and when it comes back round to
 * the first control, it must have reached every focusable clickable on the way.
 */
private fun ComposeUiTest.tabTraversal(key: String): List<Pair<String, String>> {
    val roots = roots(merged = true)
    val root = if (key.startsWith("dialog:")) dialogRoots(roots).lastOrNull() ?: return emptyList() else roots.first()
    val focusables = root.descendants().filter {
        SemanticsProperties.Focused in it.config && SemanticsActions.OnClick in it.config && SemanticsProperties.Disabled !in it.config
    }
    if (focusables.size < 2) return emptyList()
    val first = focusables.first()
    onNode(byId(first.id)).performSemanticsAction(SemanticsActions.RequestFocus)
    waitForIdle()
    var current = focused(root) ?: return listOf(nodeId(first) to "the first control did not take focus")
    // Where focus went, which may be a field inside the first control: the round ends back there
    val start = current.id
    val reached = mutableSetOf(start)
    var cycled = false
    for (press in 1..MAX_TABS) {
        onNode(byId(current.id)).performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        val next = focused(root) ?: return listOf(nodeId(first) to "focus left every control after $press Tab presses")
        if (next.id == current.id) return listOf(nodeId(next) to "focus trap: Tab press $press did not move focus")
        if (next.id == start) {
            cycled = true
            break
        }
        reached += next.id
        current = next
    }
    val missed = focusables.filter { it.id !in reached }
    return when {
        missed.isEmpty() -> emptyList()
        // Only a full round proves a control can't be reached; otherwise there were too many to tell
        cycled -> missed.map { nodeId(it) to "Tab never reaches this focusable clickable" }
        else -> listOf(nodeId(first) to "focus did not come back round within $MAX_TABS Tab presses; ${missed.size} focusable clickables not reached")
    }
}

/** Escape inside the dialog must call its dismiss, or must not for a dialog that blocks it on purpose. */
private fun ComposeUiTest.escape(key: String, probe: DismissProbe): List<Pair<String, String>> {
    val dialog = dialogRoots(roots(merged = true)).lastOrNull() ?: return listOf("-" to "no dialog window open to press Escape in")
    // Key events go to the focused node, so focus goes inside the dialog first
    val target = dialog.descendants().firstOrNull { SemanticsActions.RequestFocus in it.config }
    if (target != null) {
        onNode(byId(target.id)).performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
    }
    probe.called = false
    pressEscape(target ?: dialog)
    waitForIdle()
    val node = target?.let(::nodeId) ?: "-"
    return when {
        key in ESCAPE_BLOCKED_ON_PURPOSE && probe.called -> listOf(node to "Escape dismissed a dialog that blocks dismissal on purpose")
        key !in ESCAPE_BLOCKED_ON_PURPOSE && !probe.called -> listOf(node to "Escape did not call the dialog's dismiss")
        else -> emptyList()
    }
}

/**
 * Escape as the desktop window turns it into Back: the window passes a key event that the content
 * did not consume to a Back input on the navigation-event dispatcher, and a dialog with
 * dismissOnBackPress handles that Back by calling its onDismissRequest. A Compose test has no
 * window, so [InstallEscapeAsBack] in its content puts an input of the same kind in place.
 */
class EscapeAsBack internal constructor() : NavigationEventInput() {
    /** Back, as the window sends it for an Escape that nothing consumed. */
    fun back() = dispatchOnBackCompleted()

    companion object {
        @Volatile
        internal var current: EscapeAsBack? = null
    }
}

/** Puts an [EscapeAsBack] on the dispatcher, as the window does; call it once in a test's content. */
@Composable
fun InstallEscapeAsBack() {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher ?: return
    DisposableEffect(dispatcher) {
        val input = EscapeAsBack()
        dispatcher.addInput(input)
        EscapeAsBack.current = input
        onDispose {
            dispatcher.removeInput(input)
            if (EscapeAsBack.current === input) EscapeAsBack.current = null
        }
    }
}

/**
 * Presses Escape as the window does: into the Compose scene, where a focused node may consume it,
 * and if none does, into [EscapeAsBack]. Without a scene (another kind of test), performKeyInput.
 * The scene is internal API: a Compose update that changes it breaks only this test helper.
 */
@OptIn(InternalTestApi::class, InternalComposeUiApi::class)
private fun ComposeUiTest.pressEscape(node: SemanticsNode) {
    val scene = (this as? SkikoComposeUiTest)?.scene
    if (scene == null) {
        onNode(byId(node.id)).performKeyInput { pressKey(Key.Escape) }
        return
    }
    listOf(KeyEventType.KeyDown, KeyEventType.KeyUp).forEach { type ->
        val event = KeyEvent(Key.Escape, type)
        runOnUiThread {
            if (!scene.sendKeyEvent(event) && type == KeyEventType.KeyUp) {
                checkNotNull(EscapeAsBack.current) { "no InstallEscapeAsBack() in the test's content" }.back()
            }
        }
    }
}

// Semantics helpers

private fun ComposeUiTest.roots(merged: Boolean): List<SemanticsNode> =
    onAllNodes(isRoot(), useUnmergedTree = !merged).fetchSemanticsNodes()

/**
 * The dialog roots: those that hold a node marked as a dialog. When no root is marked (the
 * desktop dialog may not set it), every root after the window's.
 */
private fun dialogRoots(roots: List<SemanticsNode>): List<SemanticsNode> {
    val marked = roots.drop(1).filter { root -> root.descendants().any { SemanticsProperties.IsDialog in it.config } }
    return marked.ifEmpty { roots.drop(1) }
}

private fun SemanticsNode.descendants(): List<SemanticsNode> {
    val out = mutableListOf<SemanticsNode>()
    fun walk(node: SemanticsNode) {
        out += node
        node.children.forEach(::walk)
    }
    walk(this)
    return out
}

/** The focused node under [root]: each window and dialog keeps a focused node of its own. */
private fun ComposeUiTest.focused(root: SemanticsNode): SemanticsNode? =
    onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull { node -> generateSequence(node) { it.parent }.last().id == root.id }

/** A semantics id lives only for this run: used to find a node again now, never in a finding. */
private fun byId(id: Int) = SemanticsMatcher("semantics id $id") { it.id == id }

private fun SemanticsNode.text(): String? =
    config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }

private fun label(node: SemanticsNode): String? = node.text()
    ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.takeIf { it.isNotBlank() }
    ?: node.config.getOrNull(SemanticsProperties.EditableText)?.text?.takeIf { it.isNotBlank() }

private fun SemanticsNode.textLayout(): TextLayoutResult? {
    val results = mutableListOf<TextLayoutResult>()
    val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
    return if (action(results)) results.firstOrNull() else null
}

private fun SemanticsNode.ancestors(): Sequence<SemanticsNode> = generateSequence(parent) { it.parent }

private fun SemanticsNode.controlRole(): Role? =
    (sequenceOf(this) + ancestors()).firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Role)?.takeIf { role -> role in CONTROL_ROLES } }

private fun SemanticsNode.hasClickableAncestor() = (sequenceOf(this) + ancestors()).any { SemanticsActions.OnClick in it.config }

private fun roleOf(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Role)?.let { " (role $it)" } ?: ""

/**
 * A name for [node] that stays the same between runs: its test tag, else the label of the
 * nearest labelled ancestor, else the nearest ancestor's test tag, else its position.
 */
internal fun nodeId(node: SemanticsNode): String {
    node.config.getOrNull(SemanticsProperties.TestTag)?.let { return "tag:$it" }
    label(node)?.let { return "\"${short(it)}\"" }
    node.ancestors().firstNotNullOfOrNull(::label)?.let { return "in \"${short(it)}\"" }
    node.ancestors().firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.TestTag) }?.let { return "in tag:$it" }
    val bounds = node.boundsInRoot
    return "@${bounds.left.roundToInt()},${bounds.top.roundToInt()}"
}

private fun short(text: String) = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > 60) it.take(57) + "..." else it }

/**
 * One line of usability-allowlist.txt: `<key> | <RULE> | <node id> | <reason>`. [key] and [node] may
 * end with `*` to match a prefix; [line] is the entry's line in the file (1-based).
 */
data class AllowEntry(val key: String, val rule: Rule, val node: String, val reason: String, val line: Int) {
    fun matches(f: UsabilityFinding) = glob(key, f.key) && rule == f.rule && glob(node, f.node)
}

/** [pattern] equals [value], or ends with `*` and is a prefix of it. */
private fun glob(pattern: String, value: String) =
    if (pattern.endsWith("*")) value.startsWith(pattern.dropLast(1)) else pattern == value

/** The checked-in allowlist of findings that are not usability bugs, each with the reason. */
object UsabilityAllowlist {
    const val RESOURCE = "/usability-allowlist.txt"
    private const val NAME = "usability-allowlist.txt"

    /** Reads the allowlist from the test resources; throws [IllegalArgumentException] naming the line of a bad entry. */
    fun load(matrixKeys: Collection<String> = CoverageMatrix.load().map { it.key }): List<AllowEntry> = parse(
        requireNotNull(UsabilityAllowlist::class.java.getResourceAsStream(RESOURCE)) { "$RESOURCE is not on the test classpath" }
            .reader().use { it.readText() },
        matrixKeys,
    )

    /**
     * Every entry needs a reason, a known rule, and a key that is a coverage-matrix key or a prefix
     * (`dialog:*`) of at least one: a typo then fails instead of silently allowing nothing.
     */
    fun parse(text: String, matrixKeys: Collection<String>): List<AllowEntry> = text.lines().mapIndexedNotNull { index, raw ->
        val line = index + 1
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapIndexedNotNull null
        fun bad(why: String): Nothing = throw IllegalArgumentException("$NAME line $line: $why: $trimmed")
        val parts = trimmed.split("|", limit = 4).map { it.trim() }
        if (parts.size < 4) bad("expected `<key> | <RULE> | <node id> | <reason>`")
        val (key, ruleName, node, reason) = parts
        if (key.isEmpty() || node.isEmpty()) bad("the key and the node id may not be empty")
        if (reason.isEmpty()) bad("an entry needs a reason")
        val rule = Rule.entries.firstOrNull { it.name == ruleName } ?: bad("unknown rule `$ruleName`")
        if (matrixKeys.none { glob(key, it) }) bad("no matrix row for key `$key`")
        AllowEntry(key, rule, node, reason, line)
    }
}

/** A finding with the first allowlist entry that matches it, or null for a new finding. */
data class ClassifiedFinding(val finding: UsabilityFinding, val allowedBy: AllowEntry?)

/** Pairs each finding with its allowlist entry; a rule error is always new, whatever the entries say. */
fun classify(findings: List<UsabilityFinding>, entries: List<AllowEntry>): List<ClassifiedFinding> =
    findings.map { f -> ClassifiedFinding(f, if (f.node == RULE_ERROR_NODE) null else entries.firstOrNull { it.matches(f) }) }

/** The rules that fail the smoke test when enforcing; Tab traversal never does (it cannot tell a real trap from a text field). */
val ENFORCED_RULES = setOf(Rule.UNLABELLED_CLICKABLE, Rule.TEXT_OVERFLOW, Rule.ESCAPE_NOT_DISMISSING)

/** One failure message per new finding of an enforced rule. */
fun enforceFailures(findings: List<ClassifiedFinding>): List<String> = findings
    .filter { it.allowedBy == null && it.finding.rule in ENFORCED_RULES }
    .map { (f) -> "usability [${f.variant}] ${f.key} ${f.rule} ${f.node}: ${f.detail}" }

/**
 * Writes [findings] to [file] as Markdown (one section per variant, one row per finding) and next to
 * it as a `.tsv` the `usabilityReport` Gradle task merges: variant, key, rule, node, detail, and the
 * allowlist line that covers the finding (0 for a new one).
 */
fun writeUsabilityReport(findings: List<ClassifiedFinding>, file: File) {
    file.parentFile.mkdirs()
    fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
    fun tsv(s: String) = s.replace(Regex("[\t\r\n]+"), " ")
    val text = buildString {
        appendLine("# Usability findings")
        appendLine()
        appendLine("Report only unless -Pzoukan.usability.enforce=true. ${findings.size} findings, ${findings.count { it.allowedBy == null }} new.")
        appendLine()
        Rule.entries.forEach { rule -> appendLine("- $rule: ${findings.count { it.finding.rule == rule }}") }
        findings.groupBy { it.finding.variant }.forEach { (variant, rows) ->
            appendLine()
            appendLine("## $variant")
            appendLine()
            appendLine("| Key | Rule | Node | Detail | Allowlist line |")
            appendLine("|---|---|---|---|---|")
            rows.forEach { (f, by) -> appendLine("| ${cell(f.key)} | ${f.rule} | ${cell(f.node)} | ${cell(f.detail)} | ${by?.line ?: "new"} |") }
        }
    }
    file.writeText(text)
    File(file.parentFile, file.nameWithoutExtension + ".tsv").writeText(
        findings.joinToString("") { (f, by) ->
            listOf(f.variant, f.key, f.rule.name, f.node, f.detail, (by?.line ?: 0).toString()).joinToString("\t") { tsv(it) } + "\n"
        },
    )
}
