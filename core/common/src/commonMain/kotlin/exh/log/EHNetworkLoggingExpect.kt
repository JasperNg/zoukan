// ZOUKAN -->
package exh.log

import okhttp3.OkHttpClient

/** Adds a full request and response body logger when the "extreme" log level is on. */
expect fun OkHttpClient.Builder.maybeInjectEHLogger(): OkHttpClient.Builder
// ZOUKAN <--
