// CloudXtream for Windows. (phase0/ is a separate build: the feasibility tests.)
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        // The official CloudStream library, its JitPack dependencies and a copy of the AndroidX
        // artifacts Compose uses, published by .github/workflows/deps.yml
        maven("https://raw.githubusercontent.com/YumAbd/cloudxtream-desktop/maven") {
            content { includeGroupByRegex("com\\.lagradost.*|com\\.github\\..*|androidx\\..*") }
        }
        mavenCentral()
        google()
    }
}
rootProject.name = "cloudxtream-desktop"
include(":shim", ":app")
