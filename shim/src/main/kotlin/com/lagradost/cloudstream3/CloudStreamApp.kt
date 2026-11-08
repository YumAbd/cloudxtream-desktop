package com.lagradost.cloudstream3

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Desktop version of the app's Application class: extensions use its context and key store. */
class CloudStreamApp {
    companion object {
        var context: Context? = Context()
        private val keys = ConcurrentHashMap<String, Any>()

        fun <T> setKey(path: String, value: T) {
            if (value == null) keys.remove(path) else keys[path] = value
        }
        fun <T> setKey(folder: String, path: String, value: T) = setKey("$folder/$path", value)
        @Suppress("UNCHECKED_CAST")
        fun <T> getKey(path: String, defVal: T?): T? = keys[path] as? T ?: defVal
        fun <T> getKey(path: String): T? = getKey(path, null)
        fun removeKey(path: String) { keys.remove(path) }
    }
}
