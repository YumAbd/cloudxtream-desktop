// Phase 0: can the CloudStream library and the CloudXtream catalog extensions run on desktop Java?
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenLocal() // com.lagradost.api:library-jvm, published from the pinned official CloudStream commit
        mavenCentral()
        google()
        maven("https://jitpack.io") // NiceHttp
    }
}
rootProject.name = "cloudxtream-desktop-phase0"
include(":stubs", ":probe")

// One project per catalog extension, written by scripts/prepare_extensions.py.
val extensionList = file("build/extensions.tsv")
if (extensionList.exists()) {
    extensionList.readLines().filter { it.isNotBlank() }.forEach { line ->
        val name = line.split('\t')[0]
        include(":ext-$name")
        project(":ext-$name").projectDir = file("build/extprojects/$name")
    }
}
