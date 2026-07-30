pluginManagement {
    // The Kite Gradle plugin (id "com.kite.di") — one plugin id
    // replaces the KSP setup + dependency + ksp-args boilerplate in each module.
    includeBuild("kite/gradle-plugin")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kite"
include(":app")
include(":kite:compose")
include(":kite:graph-core")
include(":kite:processor")
include(":kite:rules")
include(":kite:runtime")
include(":kite:inspector")
include(":kite:inspector-noop")
 