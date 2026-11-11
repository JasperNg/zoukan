// ZOUKAN -->
package exh.log

import okhttp3.OkHttpClient

// TODO(Zoukan): add the body logger once desktop logging exists.
actual fun OkHttpClient.Builder.maybeInjectEHLogger(): OkHttpClient.Builder = this
// ZOUKAN <--
