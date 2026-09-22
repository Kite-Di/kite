// A plugin architecture: the app knows no feature by name. Every feature module
// contributes its own startup work and its own deep-link handler by implementing
// a contract from :core:plugin, and the aggregators live next to those contracts.
// Adding a feature module is the whole registration.
pluginManagement {
    val agpVersion: String by settings
    val kspVersion: String by settings
    val kiteVersion: String by settings
    repositories { mavenLocal(); google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version agpVersion
        id("com.android.library") version agpVersion
        id("com.google.devtools.ksp") version kspVersion
        id("com.kitedi") version kiteVersion
    }
}
dependencyResolutionManagement {
    repositories { mavenLocal(); google(); mavenCentral() }
}

rootProject.name = "plugin_app"
include(
    ":app",
    ":core:plugin",
    ":core:analytics",
    ":core:network",
    ":feature:orders:api",
    ":feature:orders:impl",
    ":feature:profile:api",
    ":feature:profile:impl",
    ":feature:chat:api",
    ":feature:chat:impl",
)
