// An app split into a base module and a dynamic feature — the one shape that makes
// AGP reject `compileOnly` dependencies on the base. Kite adds its @Root/@Bind/@Scoped
// vocabulary that way everywhere else, because SOURCE retention plus compileOnly is
// what keeps it out of an APK by construction, so this fixture is the only place the
// exception is exercised. Project dependencies are exempt from AGP's rule, which is
// why the in-repo demos cannot catch it: Kite has to arrive as a published artifact.
pluginManagement {
    val agpVersion: String by settings
    val kotlinVersion: String by settings
    val kspVersion: String by settings
    val kiteVersion: String by settings
    repositories { mavenLocal(); google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version agpVersion
        id("com.android.dynamic-feature") version agpVersion
        id("org.jetbrains.kotlin.android") version kotlinVersion
        id("com.google.devtools.ksp") version kspVersion
        id("com.kitedi") version kiteVersion
    }
}
dependencyResolutionManagement {
    repositories { mavenLocal(); google(); mavenCentral() }
}
rootProject.name = "dynamic"
include(":app", ":feature")
