package xyz.nulldev.androidcompat.view

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Extensions keep a hidden WebView rendering by moving it offscreen and telling it its window is
 * visible. In android.jar these View methods are stubs that throw; the desktop must accept them.
 */
class ViewOffscreenTest {

    @Test
    fun `translation is recorded and read back`() {
        val view = View(null)
        view.translationX = -10_000f
        view.translationY = 42f
        view.translationZ = 3f
        view.translationX shouldBe -10_000f
        view.translationY shouldBe 42f
        view.translationZ shouldBe 3f
    }

    @Test
    fun `telling a view its window is visible does not throw`() {
        View(null).dispatchWindowVisibilityChanged(View.VISIBLE)
    }

    @Test
    fun `a view group passes the visibility on to its children`() {
        var seen = -1
        val child = object : View(null) {
            override fun dispatchWindowVisibilityChanged(visibility: Int) {
                seen = visibility
            }
        }
        val group = object : ViewGroup(null) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
        }
        group.addView(child)
        group.dispatchWindowVisibilityChanged(View.VISIBLE)
        seen shouldBe View.VISIBLE
    }

    @Test
    fun `accessibility importance is recorded and read back`() {
        val view = View(null)
        view.importantForAccessibility shouldBe View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.importantForAccessibility shouldBe View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    @Test
    fun `WebView inherits the tested versions rather than stubs of its own`() {
        // An override between View and WebView would shadow the tested methods for every extension
        val tested = setOf(View::class.java, ViewGroup::class.java)
        listOf(
            "setTranslationX",
            "setTranslationY",
            "getTranslationX",
            "getTranslationY",
            "dispatchWindowVisibilityChanged",
            "setImportantForAccessibility",
            "getImportantForAccessibility",
        ).forEach { name ->
            WebView::class.java.methods.filter { it.name == name }.forEach { (it.declaringClass in tested) shouldBe true }
        }
    }
}
