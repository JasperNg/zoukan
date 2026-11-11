package xyz.nulldev.androidcompat

import android.app.Application
// ZOUKAN -->
import uy.kohesive.injekt.injectLazy
// ZOUKAN <--
import xyz.nulldev.androidcompat.androidimpl.CustomContext

class AndroidCompat {
    val context: CustomContext /* ZOUKAN --> */by injectLazy()/* ZOUKAN <-- */

    fun startApp(application: Application) {
        application.attach(context)
        application.onCreate()
    }
}
