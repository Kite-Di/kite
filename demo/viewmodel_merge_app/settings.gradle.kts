// :data → :feature → :app, and the only thing that needs :data is a **ViewModel**.
//
// A ViewModel is an entry point, not a binding, so :feature exports no keys at all —
// there is no constructor for the application to re-read, and ClosureValidator (which
// does exactly that) is blind here. What the application has to go on instead is
// :feature's record of *which modules it resolved keys from*; MergeValidator checks it.
// -PdataVisibility switches the edge; both outcomes are asserted in CI.
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
rootProject.name = "viewmodel_merge"
include(":app", ":feature", ":data")
