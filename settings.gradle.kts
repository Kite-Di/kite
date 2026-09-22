pluginManagement {
    // The Kite Gradle plugin (id "com.kitedi") — one plugin id
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
// The demo app the tutorial walks through. It is cut like a production codebase —
// shared infrastructure under :core, vertical features under :feature with an
// api/impl split. Every module with injectable classes applies `id("com.kitedi")`
// and owns its decisions (GraphRules.kt); :core:designsystem and the :api modules
// have no graph at all.
//
// Its files live under demo/modular_app, but the Gradle paths stay short: a
// module path becomes part of every generated registry name and every label on
// the board, and this app is meant to read like one of yours.
val demoModules = listOf(
    ":app",
    ":core:analytics",
    ":core:designsystem",
    ":core:network",
    ":feature:orders:api",
    ":feature:orders:impl",
    ":feature:profile:api",
    ":feature:profile:impl",
)
demoModules.forEach { include(it) }
// Every prefix, not just the leaves: `include(":feature:profile:api")` also
// creates the container projects :feature and :feature:profile, and Gradle
// refuses to configure one whose directory does not exist.
demoModules
    .flatMap { path -> path.drop(1).split(':').let { p -> p.indices.map { p.take(it + 1) } } }
    .distinct()
    .forEach { parts ->
        project(":" + parts.joinToString(":")).projectDir =
            file("demo/modular_app/" + parts.joinToString("/"))
    }
include(":kite:compose")
include(":kite:board")
include(":kite:graph-core")
include(":kite:processor")
include(":kite:rules")
include(":kite:runtime")
include(":kite:inspector")
include(":kite:inspector-noop")
 