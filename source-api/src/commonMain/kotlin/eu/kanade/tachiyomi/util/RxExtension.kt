// ZOUKAN -->
package eu.kanade.tachiyomi.util

import rx.Observable
import tachiyomi.core.common.util.lang.awaitSingle as awaitSingleFromCore

/** The extension API's awaitSingle; extensions link against RxExtensionKt.awaitSingle. */
suspend fun <T> Observable<T>.awaitSingle(): T = awaitSingleFromCore()
// ZOUKAN <--
