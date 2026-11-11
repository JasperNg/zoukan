package zoukan.desktop.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import zoukan.desktop.app.ExternalLinks
import zoukan.desktop.ui.common.Toasts

/**
 * The Markdown of GitHub release notes, read into blocks: Android's What's new screen renders the
 * same notes with a Markdown library. Release notes use few features, so this reads headings,
 * lists, quotes, code blocks, rules and paragraphs, and inside them bold, italic, code and links.
 */
object ReleaseNotes {
    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block

        /** [depth] is 0 for a top-level item; [marker] is "•" or the item's number with its dot */
        data class ListItem(val depth: Int, val marker: String, val text: String) : Block

        data class Quote(val text: String) : Block

        data class Code(val text: String) : Block

        data class Paragraph(val text: String) : Block

        data object Rule : Block
    }

    /** An inline piece of a block's text. */
    sealed interface Span {
        val text: String

        data class Plain(override val text: String) : Span

        data class Bold(override val text: String) : Span

        data class Italic(override val text: String) : Span

        data class Code(override val text: String) : Span

        data class Link(override val text: String, val url: String) : Span
    }

    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += Block.Paragraph(paragraph.joinToString(" ") { it.trim() })
            paragraph.clear()
        }
        val lines = markdown.replace("\r\n", "\n").lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) code += lines[i++]
                    blocks += Block.Code(code.joinToString("\n"))
                }
                line.isBlank() -> flush()
                rule.matches(line) -> {
                    flush()
                    blocks += Block.Rule
                }
                heading.matchEntire(line.trim()) != null -> {
                    flush()
                    val m = heading.matchEntire(line.trim())!!
                    blocks += Block.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                bullet.matchEntire(line) != null -> {
                    flush()
                    val m = bullet.matchEntire(line)!!
                    blocks += Block.ListItem(depth(m.groupValues[1]), "•", m.groupValues[2].trim())
                }
                numbered.matchEntire(line) != null -> {
                    flush()
                    val m = numbered.matchEntire(line)!!
                    blocks += Block.ListItem(depth(m.groupValues[1]), "${m.groupValues[2]}.", m.groupValues[3].trim())
                }
                line.trimStart().startsWith(">") -> {
                    flush()
                    blocks += Block.Quote(line.trimStart().removePrefix(">").trim())
                }
                else -> {
                    // A line under a list item that is indented belongs to the item
                    val last = blocks.lastOrNull()
                    if (paragraph.isEmpty() && last is Block.ListItem && line.startsWith("  ")) {
                        blocks[blocks.lastIndex] = last.copy(text = last.text + " " + line.trim())
                    } else {
                        paragraph += line
                    }
                }
            }
            i++
        }
        flush()
        return blocks
    }

    private fun depth(indent: String): Int = indent.replace("\t", "    ").length / 2

    private val inline = Regex(
        """\[([^\]]+)]\(([^)\s]+)\)""" + // [text](url)
            """|\*\*(.+?)\*\*|__(.+?)__""" + // bold
            """|`([^`]+)`""" + // code
            """|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""" + // italic
            """|(https?://[^\s<>()]+[^\s<>().,;:!?'"])""", // a bare address
    )

    fun spans(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var at = 0
        for (m in inline.findAll(text)) {
            if (m.range.first > at) spans += Span.Plain(text.substring(at, m.range.first))
            val g = m.groupValues
            spans += when {
                g[1].isNotEmpty() -> Span.Link(g[1], g[2])
                g[3].isNotEmpty() -> Span.Bold(g[3])
                g[4].isNotEmpty() -> Span.Bold(g[4])
                g[5].isNotEmpty() -> Span.Code(g[5])
                g[6].isNotEmpty() -> Span.Italic(g[6])
                g[7].isNotEmpty() -> Span.Italic(g[7])
                else -> Span.Link(g[8], g[8])
            }
            at = m.range.last + 1
        }
        if (at < text.length) spans += Span.Plain(text.substring(at))
        return spans
    }
}

/** Release notes in Markdown, as Android's MarkdownRender in the What's new screen. */
@Composable
fun ReleaseNotesText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { ReleaseNotes.parse(markdown) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is ReleaseNotes.Block.Heading -> Text(
                        inlineText(block.text, linkColor, codeBackground),
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            3 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    is ReleaseNotes.Block.ListItem -> Row(Modifier.padding(start = (8 + block.depth * 16).dp)) {
                        Text(block.marker, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(20.dp))
                        Text(inlineText(block.text, linkColor, codeBackground), style = MaterialTheme.typography.bodyMedium)
                    }
                    is ReleaseNotes.Block.Quote -> Text(
                        inlineText(block.text, linkColor, codeBackground),
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    is ReleaseNotes.Block.Code -> Surface(color = codeBackground, shape = MaterialTheme.shapes.small) {
                        Text(
                            block.text,
                            style = TextStyle(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        )
                    }
                    is ReleaseNotes.Block.Paragraph ->
                        Text(inlineText(block.text, linkColor, codeBackground), style = MaterialTheme.typography.bodyMedium)
                    ReleaseNotes.Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

private fun inlineText(text: String, linkColor: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    ReleaseNotes.spans(text).forEach { span ->
        when (span) {
            is ReleaseNotes.Span.Plain -> append(span.text)
            is ReleaseNotes.Span.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            is ReleaseNotes.Span.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
            is ReleaseNotes.Span.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                append(span.text)
            }
            is ReleaseNotes.Span.Link -> {
                // Only web addresses open; anything else stays plain text
                if (ExternalLinks.isWebUrl(span.url)) {
                    val link = LinkAnnotation.Clickable(
                        tag = span.url,
                        styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ) {
                        runCatching { ExternalLinks.browse(span.url) }.onFailure { Toasts.error("Could not open the page", it) }
                    }
                    withLink(link) { append(span.text) }
                } else {
                    append(span.text)
                }
            }
        }
    }
}
