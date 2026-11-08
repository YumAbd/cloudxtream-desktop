// Desktop stand-ins for the Android classes that CloudStream extensions use (see phase0/RESULTS.md).
plugins {
    kotlin("jvm")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
dependencies {
    api(providers.gradleProperty("cxLibrary").get())
    // The library only declares these for runtime; extensions and the app compile against them.
    api("com.github.Blatzar:NiceHttp:0.4.18")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    api("org.jsoup:jsoup:1.22.1")
    api("org.json:json:20260814") // Android ships org.json; extensions use it directly
    api("org.jspecify:jspecify:1.0.0")
}
