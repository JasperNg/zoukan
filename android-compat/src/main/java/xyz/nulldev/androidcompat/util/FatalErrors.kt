@file:JvmName("FatalErrors")

package xyz.nulldev.androidcompat.util

// ZOUKAN: whole file -->

/*
 * The first statement of a catch-all (`catch (e: Throwable)`, `catch (e: Error)`) in the app's
 * own code: the failures the catch must not keep running after go on up to the crash report.
 * `FatalErrorCatchScanTest` refuses a catch-all without one. In android-compat, the lowest module
 * the app, the extension loader and Zoukan's android-compat code all depend on.
 *
 * A coroutine's cancellation is not handled here: catch `CancellationException` first and rethrow
 * it where the code is in a coroutine (`CancellationSourceScanTest`).
 */

/**
 * Rethrows this when it is fatal: a [VirtualMachineError] (out of memory, stack overflow, an
 * internal error of the VM) or a [LinkageError] (a class or method missing from the app's own
 * code). After one of these the app is in a state nobody tested, so the catch must not go on as if
 * the action had only failed.
 */
fun Throwable.rethrowIfFatal() {
    if (this is VirtualMachineError || this is LinkageError) throw this
}

/**
 * Rethrows this when the VM itself failed: a [VirtualMachineError] other than a
 * [StackOverflowError]. Only for a catch on a path that runs extension code (or loads a native
 * library), where the two other errors are a fault of the code that was called and the app shows
 * them as an error and goes on:
 *
 * - a [LinkageError]: an extension built against another API (`NoClassDefFoundError`,
 *   `NoSuchMethodError`), shown as an outdated extension, or a native library that would not load;
 * - a [StackOverflowError]: an extension's own runaway recursion. The stack is unwound by the
 *   time the catch runs, as the main looper already assumes.
 *
 * `FatalErrorCatchScanTest` accepts this form only at the sites it lists, each with what extension
 * code the path runs.
 */
fun Throwable.rethrowIfVmFailure() {
    if (this is VirtualMachineError && this !is StackOverflowError) throw this
}

// ZOUKAN: whole file <--
