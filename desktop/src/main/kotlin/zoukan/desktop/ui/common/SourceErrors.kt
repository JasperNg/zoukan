package zoukan.desktop.ui.common

import eu.kanade.tachiyomi.network.HttpException
import tachiyomi.i18n.MR
import zoukan.desktop.compat.localized
import zoukan.i18n.desktop.DMR

/**
 * The text a screen shows for a source request that failed: what happened and what to do, never a
 * class name, a status line or an address. The caller logs the original.
 */
internal fun sourceErrorText(e: Throwable): String = when {
    e is HttpException -> localized(MR.strings.exception_http, e.code)
    e is java.net.UnknownHostException && !e.message.isNullOrBlank() -> localized(MR.strings.exception_unknown_host, e.message.orEmpty())
    e is java.net.SocketTimeoutException || e is java.util.concurrent.TimeoutException ->
        localized(DMR.strings.source_timed_out)
    e is LinkageError -> localized(DMR.strings.source_extension_outdated)
    // Extensions write user-facing text this way, for example "Log in via WebView"
    (e::class == Exception::class || e::class == java.io.IOException::class) && !e.message.isNullOrBlank() ->
        e.message.orEmpty()
    // The reader's image decoder words this for the user ("unsupported image format")
    e is UnreadableImageException -> e.message
    else -> localized(DMR.strings.source_request_failed)
}

/** A page that cannot be decoded, with a message already worded for the user (in the runtime's image package, which the downloads use too). */
internal typealias UnreadableImageException = zoukan.desktop.image.UnreadableImageException
