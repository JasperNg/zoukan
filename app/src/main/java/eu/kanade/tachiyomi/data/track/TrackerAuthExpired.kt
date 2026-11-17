package eu.kanade.tachiyomi.data.track

import okhttp3.Response
import java.io.IOException

// ZOUKAN: new file. MyAnimeList's handling of a refused token (the `trackAuthExpired` preference and
// an IOException that says the login has expired), shared with the other OAuth trackers' interceptors.
// Signing in again clears the flag (`TrackPreferences.setCredentials`), as signing out does.

class TrackerLoginExpired(tracker: Tracker) : IOException("${tracker.name}: Login has expired")

/** Fails the call before the network once a token was refused, as MAL's interceptor does. */
fun BaseTracker.throwIfLoginExpired() {
    if (trackPreferences.trackAuthExpired(this).get()) throw TrackerLoginExpired(this)
}

/** Marks the login expired and fails the call if the server refused the token (401). */
fun BaseTracker.checkTokenAccepted(response: Response): Response {
    if (response.code == 401) {
        response.close()
        trackPreferences.trackAuthExpired(this).set(true)
        throw TrackerLoginExpired(this)
    }
    return response
}

/** A new token from signing in or a refresh: the login is no longer expired. */
fun BaseTracker.clearLoginExpired() {
    trackPreferences.trackAuthExpired(this).set(false)
}
