package org.apache.http

/** The status codes the shared sync services use; Android has them from its Apache HTTP stubs. */
object HttpStatus {
    const val SC_OK = 200
    const val SC_NOT_MODIFIED = 304
    const val SC_UNAUTHORIZED = 401
    const val SC_NOT_FOUND = 404
    const val SC_CONFLICT = 409
    const val SC_PRECONDITION_FAILED = 412
}
