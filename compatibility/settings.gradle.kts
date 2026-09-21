// A consumer's project, built against whatever toolchain versions are passed in.
// Kite itself comes from mavenLocal, so this checks the working tree, not a release.
pluginManagement {
    val agpVersion: String by settings
    val kotlinVersion: String by settings
    val kspVersion: String by settings
    val kiteVersion: String by settings

    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version agpVersion
        id("org.jetbrains.kotlin.android") version kotlinVersion
        id("com.google.devtools.ksp") version kspVersion
        id("com.kitedi") version kiteVersion
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "compatibility"
include(":app")
