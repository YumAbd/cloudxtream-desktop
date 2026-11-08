package android.util

/** Desktop stand-in for android.util.Log: prints to standard error. */
object Log {
    @JvmStatic fun d(tag: String?, msg: String?): Int = print("D", tag, msg)
    @JvmStatic fun i(tag: String?, msg: String?): Int = print("I", tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?): Int = print("W", tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?): Int = print("E", tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = print("E", tag, msg + " " + tr)
    @JvmStatic fun v(tag: String?, msg: String?): Int = print("V", tag, msg)
    private fun print(level: String, tag: String?, msg: String?): Int {
        System.err.println("$level/$tag: $msg")
        return 0
    }
}
