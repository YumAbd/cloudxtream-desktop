package android.content.res

import android.util.DisplayMetrics

open class Resources {
    open fun getDisplayMetrics(): DisplayMetrics = metrics
    /** Extension resources (layouts, drawables) are only used by settings screens; none on desktop yet. */
    open fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int = 0

    companion object {
        @JvmField val DESKTOP = Resources()
        private val metrics = DisplayMetrics()
    }
}
