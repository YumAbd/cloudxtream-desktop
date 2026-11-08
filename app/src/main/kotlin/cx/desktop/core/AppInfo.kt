package cx.desktop.core

object AppInfo {
    /** Set by the build (jpackage) from the release tag; "dev" when run from Gradle. */
    val version: String = AppInfo::class.java.`package`?.implementationVersion ?: System.getProperty("cx.version") ?: "dev"
}
