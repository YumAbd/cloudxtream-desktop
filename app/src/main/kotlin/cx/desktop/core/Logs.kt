package cx.desktop.core

import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream

/** The installed app has no console: its output goes to %APPDATA%\CloudXtream\logs\app.log. */
object Logs {
    val file: File = File(File(AppDirs.root, "logs").apply { mkdirs() }, "app.log")

    fun start() {
        if (System.console() != null || System.getProperty("cx.version") == null) return
        runCatching {
            if (file.length() > 5_000_000) file.renameTo(File(file.parentFile, "app.old.log"))
            val stream = PrintStream(FileOutputStream(file, true), true, Charsets.UTF_8)
            stream.println("---- CloudXtream ${AppInfo.version} started ${java.time.LocalDateTime.now()} ----")
            System.setOut(stream)
            System.setErr(stream)
        }
    }
}
