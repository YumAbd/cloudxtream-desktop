package cx.desktop

import cx.desktop.player.Vlc
import java.io.File

/** CI smoke test from Gradle (see SelfTest). Args: output PNG. */
fun main(args: Array<String>) {
    val ok = SelfTest.run(File(args.getOrElse(0) { "smoke-frame.png" })) { println(it) }
    Vlc.release()
    System.exit(if (ok) 0 else 1)
}
