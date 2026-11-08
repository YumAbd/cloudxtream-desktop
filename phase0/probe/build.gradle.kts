plugins {
    application
}
application {
    mainClass.set("cx.probe.MainKt")
    // Classes converted from Android DEX can refer to Android UI classes that only their settings
    // screens use. Without bytecode verification those references only fail if that code runs.
    applicationDefaultJvmArgs = listOf("-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote", "-Xss4m")
}
dependencies {
    "implementation"(project(":stubs"))
    "implementation"("de.femtopedia.dex2jar:dex-translator:2.4.38")
}
