package com.lagradost.cloudstream3.plugins

import android.content.Context

/** Desktop version of the app's Plugin class (the Android one lives in the app, not the library). */
abstract class Plugin : BasePlugin() {
    open fun load(context: Context) {
        load()
    }

    var resources: android.content.res.Resources? = null
    var openSettings: ((context: Context) -> Unit)? = null
}
