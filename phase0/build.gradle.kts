import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0" apply false
}

// The library built from the pinned official CloudStream commit (the CI passes the exact version).
val cxLibrary = providers.gradleProperty("cxLibrary").orElse("com.lagradost.api:library-jvm:1.0.1").get()

// Libraries the CloudStream library is built with; extensions compile against them on Android too.
val libraryDependencies = listOf(
    "org.jsoup:jsoup:1.22.1",
    "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1",
    "com.github.Blatzar:NiceHttp:0.4.18",
    "org.mozilla:rhino:1.8.1",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0",
    "org.json:json:20260814", // Android ships org.json; extensions use it directly
)

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(17)
        compilerOptions {
            optIn.addAll("com.lagradost.cloudstream3.Prerelease", "com.lagradost.cloudstream3.InternalAPI")
        }
    }
    dependencies {
        "implementation"(cxLibrary)
        libraryDependencies.forEach { "implementation"(it) }
    }
}

// name <TAB> extension source directory <TAB> extra dependencies (comma separated)
val extensionList = file("build/extensions.tsv")
val extensionProjects = mutableListOf<String>()
if (extensionList.exists()) {
    extensionList.readLines().filter { it.isNotBlank() }.forEach { line ->
        val parts = line.split('\t')
        val name = parts[0]
        val extra = parts.getOrNull(2).orEmpty().split(',').filter { it.isNotBlank() }
        extensionProjects += ":ext-$name"
        project(":ext-$name") {
            extensions.configure<KotlinJvmProjectExtension> {
                sourceSets.getByName("main").kotlin.srcDir(parts[1])
            }
            dependencies {
                "implementation"(project(":stubs"))
                extra.forEach { "implementation"(it) }
            }
        }
    }
}

// Compiles every extension; run with --continue so one failure does not stop the others.
tasks.register("compileExtensions") {
    dependsOn(extensionProjects.map { "$it:jar" })
}
