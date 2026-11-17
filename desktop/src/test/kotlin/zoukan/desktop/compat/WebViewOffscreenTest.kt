package zoukan.desktop.compat

import android.view.View
import android.webkit.WebView
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Extensions move a hidden WebView offscreen and tell it its window is visible, so it keeps
 * rendering. View's versions of these are stubs that throw; WebView must override them.
 */
class WebViewOffscreenTest {

    @Test
    fun `the calls that keep a hidden WebView rendering are WebView's own`() {
        listOf(
            WebView::class.java.getDeclaredMethod("dispatchWindowVisibilityChanged", Int::class.javaPrimitiveType),
            WebView::class.java.getDeclaredMethod("setTranslationX", Float::class.javaPrimitiveType),
            WebView::class.java.getDeclaredMethod("setTranslationY", Float::class.javaPrimitiveType),
            WebView::class.java.getDeclaredMethod("getTranslationX"),
            WebView::class.java.getDeclaredMethod("getTranslationY"),
        ).forEach { it.declaringClass shouldBe WebView::class.java }
        View::class.java.isAssignableFrom(WebView::class.java) shouldBe true
    }
}
