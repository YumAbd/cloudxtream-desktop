plugins {
    application
}
application {
    mainClass.set("cx.probe.MainKt")
}
dependencies {
    "implementation"(project(":stubs"))
}
