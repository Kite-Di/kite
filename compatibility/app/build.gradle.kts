plugins {
    id("com.android.application")
}

// AGP 9 carries Kotlin support itself and fails if the standalone plugin is
// applied; AGP 8 has no Kotlin without it. Applied before Kite, which brings KSP.
if (providers.gradleProperty("agpVersion").get().startsWith("8")) {
    apply(plugin = "org.jetbrains.kotlin.android")
}
apply(plugin = "com.kitedi")

android {
    namespace = "com.example.compat"
    // The property form, not the AGP 9 block: this file has to parse on AGP 8 too.
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.compat"
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
