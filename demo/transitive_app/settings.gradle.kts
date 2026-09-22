// :deep → :mid → :app. With `implementation` between mid and deep, deep never
// reaches the app's compile classpath, its registry is missing from the merge,
// and before the closure check the app built fine and died on the device.
// -PdeepVisibility switches the edge; both outcomes are asserted in CI.
pluginManagement {
    val agpVersion: String by settings
    val kotlinVersion: String by settings
    val kspVersion: String by settings
    val kiteVersion: String by settings
    repositories { mavenLocal(); google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version agpVersion
        id("com.android.library") version agpVersion
        id("org.jetbrains.kotlin.android") version kotlinVersion
        id("com.google.devtools.ksp") version kspVersion
        id("com.kitedi") version kiteVersion
    }
}
dependencyResolutionManagement {
    repositories { mavenLocal(); google(); mavenCentral() }
}
rootProject.name = "transitive"
include(":app", ":mid", ":deep")
