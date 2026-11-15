package zoukan.desktop.compat

import android.text.Html
import android.text.Spanned
import android.text.style.MetricAffectingSpan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Html.fromHtml gives the plain text Android's converter lays out, as a consistent CharSequence. */
class HtmlCompatTest {

    @Test
    fun `entities are decoded`() {
        Html.fromHtml("Tom &amp; Jerry &lt;3 &quot;x&quot; &#39;y&#39;").toString() shouldBe "Tom & Jerry <3 \"x\" 'y'"
    }

    @Test
    fun `br is a newline`() {
        Html.fromHtml("one<br>two<br/>three").toString() shouldBe "one\ntwo\nthree"
    }

    @Test
    fun `paragraphs are separated by a blank line, or one newline in compact mode`() {
        Html.fromHtml("<p>one</p><p>two</p>").toString() shouldBe "one\n\ntwo\n\n"
        Html.fromHtml("<p>one</p><p>two</p>", Html.FROM_HTML_MODE_COMPACT).toString() shouldBe "one\ntwo\n"
        Html.fromHtml("<div>a</div><h1>b</h1><ul><li>c</li><li>d</li></ul>", Html.FROM_HTML_MODE_COMPACT)
            .toString() shouldBe "a\nb\nc\nd\n"
    }

    @Test
    fun `whitespace collapses and nbsp stays a no-break space`() {
        Html.fromHtml("  a \n\t b&nbsp;c  ").toString() shouldBe "a b c "
    }

    @Test
    fun `tags are dropped and scripts are not text`() {
        Html.fromHtml("<b>bold</b> <a href=\"x\">link</a><script>var a = 1;</script>").toString() shouldBe "bold link"
    }

    @Test
    fun `length, charAt, subSequence and trim agree with toString`() {
        val text = Html.fromHtml("<p>Tom &amp; Jerry</p>")
        val string = text.toString()
        string shouldBe "Tom & Jerry\n\n"
        text.length shouldBe string.length
        text[4] shouldBe '&'
        text.subSequence(0, 3).toString() shouldBe "Tom"
        text.trim().toString() shouldBe "Tom & Jerry"
        text.isBlank() shouldBe false
        android.text.TextUtils.isEmpty(text) shouldBe false
        StringBuilder().append(text).toString() shouldBe string
    }

    @Test
    fun `empty input is empty`() {
        val text = Html.fromHtml("")
        text.length shouldBe 0
        text.isBlank() shouldBe true
        Html.fromHtml("<p></p>").toString() shouldBe ""
    }

    @Test
    fun `there are no spans`() {
        val text: Spanned = Html.fromHtml("<a href=\"x\">link</a>")
        text.getSpans(0, text.length, MetricAffectingSpan::class.java).size shouldBe 0
        text.getSpans(0, text.length, Any::class.java).size shouldBe 0
    }

    @Test
    fun `the ImageGetter overload works`() {
        Html.fromHtml("a<br>b", null, null).toString() shouldBe "a\nb"
    }
}
