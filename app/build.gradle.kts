import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll("com.lagradost.cloudstream3.Prerelease", "com.lagradost.cloudstream3.InternalAPI")
    }
}

// Version from the release tag (v1.2.3), "1.0.0" for local builds.
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "1.0.0"

dependencies {
    implementation(project(":shim"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("de.femtopedia.dex2jar:dex-translator:2.4.38")
    implementation("uk.co.caprica:vlcj:4.12.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
}

tasks.jar {
    manifest { attributes("Implementation-Version" to appVersion) }
}

compose.desktop {
    application {
        mainClass = "cx.desktop.MainKt"
        jvmArgs += listOf(
            // Extensions converted from Android may refer to Android UI classes that only their
            // settings screens use; without verification those references only fail if that code runs.
            "-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote",
            "-Xss4m", "-Dfile.encoding=UTF-8", "-Dcx.version=$appVersion",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "CloudXtream"
            packageVersion = appVersion
            vendor = "Leque"
            description = "CloudXtream by Leque"
            copyright = "CloudStream contributors (GPL-3.0), CloudXtream"
            licenseFile.set(rootProject.file("LICENSE"))
            // Bundled VLC (resources/windows/vlc), downloaded by the release workflow.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules("java.net.http", "java.sql", "java.naming", "jdk.unsupported", "jdk.crypto.ec", "jdk.accessibility")
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menu = true
                menuGroup = "CloudXtream"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                // Never change: Windows uses it to upgrade an installed CloudXtream in place.
                upgradeUuid = "6f4d2a83-1c7e-4b6a-9a51-2f0f6c2d9e47"
            }
        }
    }
}

// Used by .github/workflows/deps.yml to download every dependency (see the androidx job there).
tasks.register("resolveAll") {
    doLast {
        listOf("compileClasspath", "runtimeClasspath").forEach { configurations.getByName(it).resolve() }
    }
}
