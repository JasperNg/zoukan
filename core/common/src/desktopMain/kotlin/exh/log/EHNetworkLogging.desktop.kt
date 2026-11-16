// ZOUKAN -->
package exh.log

import okhttp3.OkHttpClient

// No logger on desktop, on purpose: Android's "extreme" level logs whole requests and responses,
// cookies included, and the desktop log in <data>/logs is plain text that users attach to bug
// reports. A future debug logger must log headers only and redact Cookie, Set-Cookie and
// Authorization (HttpLoggingInterceptor.redactHeader).
actual fun OkHttpClient.Builder.maybeInjectEHLogger(): OkHttpClient.Builder = this
// ZOUKAN <--
