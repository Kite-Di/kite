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
// The demo app is cut like a production codebase — shared infrastructure under
// :core, vertical features under :feature with an api/impl split. Every module
// with injectable classes applies `id("com.kite.di")` and owns its
// decisions (GraphRules.kt); :core:designsystem and the :api modules have no
// graph at all.
include(":core:analytics")
include(":core:designsystem")
include(":core:network")
include(":feature:orders:api")
include(":feature:orders:impl")
include(":feature:profile:api")
include(":feature:profile:impl")
include(":kite:compose")
include(":kite:board")
include(":kite:graph-core")
include(":kite:processor")
include(":kite:rules")
include(":kite:runtime")
include(":kite:inspector")
include(":kite:inspector-noop")
 