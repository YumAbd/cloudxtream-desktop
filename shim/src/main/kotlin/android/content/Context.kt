package android.content

import android.content.res.Resources
import java.util.concurrent.ConcurrentHashMap

/** Desktop stand-in for android.content.Context: what extensions use outside of their settings screens. */
open class Context {
    open fun getApplicationContext(): Context = this
    open fun getResources(): Resources = Resources.DESKTOP
    open fun getPackageName(): String = "com.cloudxtream.desktop"
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferences.getOrPut(name) { MemoryPreferences() }

    companion object {
        private val preferences = ConcurrentHashMap<String, SharedPreferences>()
    }
}

open class ContextWrapper(private val base: Context? = null) : Context() {
    open fun getBaseContext(): Context = base ?: this
}
