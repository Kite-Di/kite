plugins {
    id("com.android.library")
    id("com.kitedi")
}

android {
    namespace = "com.example.mid"
    compileSdk = 34
    defaultConfig { minSdk = 23 }
}

dependencies {
    // implementation by default — the case that used to slip through.
    add(providers.gradleProperty("deepVisibility").get(), project(":deep"))
}
