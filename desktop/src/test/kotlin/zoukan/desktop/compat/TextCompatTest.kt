package zoukan.desktop.compat

import android.app.ActivityManager
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.icu.text.BreakIterator
import android.icu.text.Collator
import android.icu.text.Normalizer2
import android.icu.text.RuleBasedCollator
import android.icu.text.SearchIterator
import android.icu.text.StringSearch
import android.os.LocaleList
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.DisplayMetrics
import android.util.Patterns
import android.view.Display
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import xyz.nulldev.androidcompat.res.DesktopResources
import java.text.StringCharacterIterator
import java.util.Locale

class TextCompatTest {

    @Test
    fun `default display matches DisplayMetrics defaults`() {
        val metrics = DisplayMetrics().apply { setToDefaults() }
        metrics.widthPixels shouldBe DesktopResources.SCREEN_WIDTH_PIXELS
        metrics.heightPixels shouldBe DesktopResources.SCREEN_HEIGHT_PIXELS
        val display = DisplayManager.INSTANCE.getDisplay(Display.DEFAULT_DISPLAY)
        display.width shouldBe DesktopResources.SCREEN_WIDTH_PIXELS
        display.height shouldBe DesktopResources.SCREEN_HEIGHT_PIXELS
    }

    @Test
    fun `LocaleList default is the JVM locale`() {
        val list = LocaleList.getDefault()
        list.size() shouldBe 1
        list.get(0) shouldBe Locale.getDefault()
        LocaleList.forLanguageTags("en-US,fr").toLanguageTags() shouldBe "en-US,fr"
    }

    @Test
    fun `Patterns are compiled`() {
        Patterns.EMAIL_ADDRESS.matcher("someone@example.com").matches() shouldBe true
        Patterns.EMAIL_ADDRESS.matcher("not an email").matches() shouldBe false
        Patterns.WEB_URL.matcher("https://example.com/path?q=1").matches() shouldBe true
        Patterns.IP_ADDRESS.matcher("192.168.1.10").matches() shouldBe true
        Patterns.DOMAIN_NAME.matcher("example.org").matches() shouldBe true
    }

    @Test
    fun `Collator sorts numbers numerically`() {
        val collator = Collator.getInstance() as RuleBasedCollator
        collator.strength = Collator.PRIMARY
        collator.numericCollation = true
        collator.freeze()
        listOf("Chapter 10", "chapter 2", "Chapter 1").sortedWith(collator) shouldBe
            listOf("Chapter 1", "chapter 2", "Chapter 10")
        collator.compare("a", "A") shouldBe 0
    }

    @Test
    fun `BreakIterator finds words`() {
        val words = BreakIterator.getWordInstance()
        words.setText("Hello, world")
        val found = mutableListOf<String>()
        var start = words.first()
        var end = words.next()
        while (end != BreakIterator.DONE) {
            if (words.ruleStatus != BreakIterator.WORD_NONE) found += "Hello, world".substring(start, end)
            start = end
            end = words.next()
        }
        found shouldBe listOf("Hello", "world")
    }

    @Test
    fun `Normalizer2 case folds`() {
        Normalizer2.getNFKCCasefoldInstance().normalize("ＡＢＣ") shouldBe "abc"
    }

    @Test
    fun `StringSearch matches ignoring case`() {
        val collator = Collator.getInstance(Locale.ENGLISH) as RuleBasedCollator
        collator.strength = Collator.PRIMARY
        val search = StringSearch("one", StringCharacterIterator("One two one"), collator)
        search.setOverlapping(false)
        search.first() shouldBe 0
        search.matchedText shouldBe "One"
        search.next() shouldBe 8
        search.next() shouldBe SearchIterator.DONE
    }

    private class BiggerSpan : MetricAffectingSpan() {
        override fun updateMeasureState(p: TextPaint) = Unit
        override fun updateDrawState(tp: TextPaint) = Unit
    }

    @Test
    fun `SpannableString keeps spans`() {
        val text = SpannableString("Hello world")
        val span = BiggerSpan()
        text.setSpan(span, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.getSpans(0, text.length, MetricAffectingSpan::class.java).toList() shouldBe listOf(span)
        text.getSpanStart(span) shouldBe 0
        text.getSpanEnd(span) shouldBe 5
        text.getSpans(6, 11, Any::class.java).size shouldBe 0
        (text.subSequence(0, 3) as Spanned).getSpanEnd(span) shouldBe 3
        text.removeSpan(span)
        text.getSpans(0, text.length, Any::class.java).size shouldBe 0
        text.toString() shouldBe "Hello world"
    }

    @Test
    fun `system Resources have desktop metrics and the default locale`() {
        val resources = Resources.getSystem()
        resources.displayMetrics.density shouldBe 1f
        (resources.displayMetrics.widthPixels > 0) shouldBe true
        resources.configuration.locales.get(0) shouldBe Locale.getDefault()
    }

    @Test
    fun `ActivityManager reports memory`() {
        val info = ActivityManager.MemoryInfo()
        ActivityManager.INSTANCE.getMemoryInfo(info)
        info.totalMem shouldBeGreaterThan 0L
        info.availMem shouldBeGreaterThan 0L
    }

    @Test
    fun `Preference click listener round trip`() {
        val pref = Preference(null)
        pref.performClick() shouldBe false
        var clicks = 0
        val listener = Preference.OnPreferenceClickListener {
            clicks++
            it.summary = "clicked"
            true
        }
        pref.onPreferenceClickListener = listener
        pref.onPreferenceClickListener shouldBe listener
        pref.performClick() shouldBe true
        clicks shouldBe 1
        pref.summary shouldBe "clicked"
    }

    @Test
    fun `MultiSelectListPreference keeps set values`() {
        val pref = MultiSelectListPreference(null)
        pref.entryValues = arrayOf("a", "b")
        pref.values = setOf("b")
        pref.values shouldBe setOf("b")
        pref.findIndexOfValue("b") shouldBe 1
    }
}
