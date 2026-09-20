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
// The demo app the tutorial walks through, under demo/tutorial_app. It is cut
// like a production codebase — shared infrastructure under :core, vertical
// features under :feature with an api/impl split. Every module with injectable
// classes applies `id("com.kitedi")` and owns its decisions (GraphRules.kt);
// :core:designsystem and the :api modules have no graph at all.
include(":demo:tutorial_app:app")
include(":demo:tutorial_app:core:analytics")
include(":demo:tutorial_app:core:designsystem")
include(":demo:tutorial_app:core:network")
include(":demo:tutorial_app:feature:orders:api")
include(":demo:tutorial_app:feature:orders:impl")
include(":demo:tutorial_app:feature:profile:api")
include(":demo:tutorial_app:feature:profile:impl")
include(":kite:compose")
include(":kite:board")
include(":kite:graph-core")
include(":kite:processor")
include(":kite:rules")
include(":kite:runtime")
include(":kite:inspector")
include(":kite:inspector-noop")
 