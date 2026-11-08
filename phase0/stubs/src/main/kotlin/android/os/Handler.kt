package android.os

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class Looper private constructor() {
    companion object {
        private val main = Looper()
        @JvmStatic fun getMainLooper(): Looper = main
        @JvmStatic fun myLooper(): Looper = main
    }
}

/** Runs posted work on one background thread (there is no Android UI thread on desktop). */
open class Handler(@Suppress("UNUSED_PARAMETER") looper: Looper? = null) {
    private val pending = HashMap<Runnable, ScheduledFuture<*>>()
    fun post(r: Runnable): Boolean = postDelayed(r, 0)
    fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        synchronized(pending) { pending[r] = executor.schedule({ synchronized(pending) { pending.remove(r) }; r.run() }, delayMillis, TimeUnit.MILLISECONDS) }
        return true
    }
    fun removeCallbacks(r: Runnable) {
        synchronized(pending) { pending.remove(r)?.cancel(false) }
    }

    companion object {
        private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "android-handler").apply { isDaemon = true } }
    }
}
