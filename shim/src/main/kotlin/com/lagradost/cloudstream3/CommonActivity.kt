package com.lagradost.cloudstream3

import android.app.Activity

/** On Android this is the visible screen; extensions only reach it from their settings screens. */
object CommonActivity {
    var activity: Activity? = null
    fun showToast(message: String?, duration: Int? = null) {
        System.err.println("Toast: $message")
    }
}
