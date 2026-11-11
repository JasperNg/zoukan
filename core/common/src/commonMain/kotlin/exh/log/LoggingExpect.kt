// ZOUKAN -->
package exh.log

// The rest of the xLog functions are Android-only for now; add an expect here when shared
// code needs one.

/** Logs an error with its stack trace, tagged with the receiver's class name. */
expect fun Any.xLogE(log: String, e: Throwable)
// ZOUKAN <--
