plugins {
    id("com.android.library")
    id("com.kitedi")
}

android {
    namespace = "com.example.lookup"
    compileSdk = 34
    defaultConfig { minSdk = 23 }
}

dependencies {
    // implementation by default — the case that slipped through.
    add(providers.gradleProperty("dataVisibility").get(), project(":data"))
}
