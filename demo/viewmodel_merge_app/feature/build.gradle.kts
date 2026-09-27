plugins {
    id("com.android.library")
    id("com.kitedi")
}

android {
    namespace = "com.example.feature"
    compileSdk = 34
    defaultConfig { minSdk = 23 }
}

dependencies {
    // implementation by default — the case that slipped through: nothing in this
    // module is a binding, so the application had nothing to re-read.
    add(providers.gradleProperty("dataVisibility").get(), project(":data"))

    implementation("androidx.lifecycle:lifecycle-viewmodel:2.6.2")
    // The generated adapters come in Activity and Fragment flavours whether this
    // module has either or not, so both have to be resolvable.
    implementation("androidx.activity:activity:1.8.0")
    implementation("androidx.fragment:fragment:1.6.2")
}
