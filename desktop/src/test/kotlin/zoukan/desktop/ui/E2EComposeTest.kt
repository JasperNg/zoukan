package zoukan.desktop.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * The effect context for end-to-end UI tests. By default the Compose test runs effects on an
 * UnconfinedTestDispatcher, so a collector (collectAsState, produceState) resumes on whichever
 * thread emitted: in these tests the Android main looper, which is Dispatchers.Main here. Its
 * state write then races the composition on the test thread, and about one run in ten on Windows
 * the screen never recomposed for it. In the app, effects and composition share the Swing thread.
 * A StandardTestDispatcher queues every resumption and runs it on the test thread between frames,
 * as the app does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun e2eEffectContext(): CoroutineContext = StandardTestDispatcher()
