// :data → (:viewmodel | :lookup) → :app, and neither middle module lets the application
// see what it needs.
//
// :viewmodel holds one ViewModel — an entry point, not a binding — so it exports no
// keys and there is no constructor for ClosureValidator to re-read; what catches it is
// the module's record of which modules it resolved keys from.
//
// :lookup is the harder half: its only use of :data is `Kite.get<Repository>()` inside
// a function body, which KSP does not read at all, so nothing can record the demand.
// There the build goes by the other fact it can see — that :data's graph ships in the
// app unmerged.
// -PdataVisibility switches the edge, -PdemandShape the kind of demand; CI asserts all
// three outcomes.
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
include(":app", ":viewmodel", ":lookup", ":data")
