package xyz.nulldev.androidcompat.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.io.IOException

/** Which failures each catch-all helper lets through (FatalErrors.kt). */
class FatalErrorsTest {
    private fun rethrown(e: Throwable, helper: Throwable.() -> Unit): Boolean = try {
        e.helper()
        false
    } catch (thrown: Throwable) {
        thrown shouldBeSameInstanceAs e
        true
    }

    @Test
    fun `rethrowIfFatal rethrows VM errors and linkage errors, and nothing else`() {
        rethrown(OutOfMemoryError(), Throwable::rethrowIfFatal) shouldBe true
        rethrown(StackOverflowError(), Throwable::rethrowIfFatal) shouldBe true
        rethrown(InternalError(), Throwable::rethrowIfFatal) shouldBe true
        rethrown(NoClassDefFoundError("x"), Throwable::rethrowIfFatal) shouldBe true
        rethrown(NoSuchMethodError("x"), Throwable::rethrowIfFatal) shouldBe true
        rethrown(IOException(), Throwable::rethrowIfFatal) shouldBe false
        rethrown(IllegalStateException(), Throwable::rethrowIfFatal) shouldBe false
        rethrown(AssertionError(), Throwable::rethrowIfFatal) shouldBe false
    }

    @Test
    fun `rethrowIfVmFailure keeps an extension's linkage error and stack overflow`() {
        rethrown(OutOfMemoryError(), Throwable::rethrowIfVmFailure) shouldBe true
        rethrown(InternalError(), Throwable::rethrowIfVmFailure) shouldBe true
        rethrown(StackOverflowError(), Throwable::rethrowIfVmFailure) shouldBe false
        rethrown(NoClassDefFoundError("x"), Throwable::rethrowIfVmFailure) shouldBe false
        rethrown(IOException(), Throwable::rethrowIfVmFailure) shouldBe false
    }

    @Test
    fun `a catch with the helper first lets an OutOfMemoryError out and handles an exception`() {
        fun guarded(failure: Throwable): String = try {
            throw failure
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            "handled"
        }
        guarded(IOException()) shouldBe "handled"
        shouldThrow<OutOfMemoryError> { guarded(OutOfMemoryError()) }
    }
}
