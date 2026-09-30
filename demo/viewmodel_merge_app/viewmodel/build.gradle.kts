plugins {
    id("com.android.library")
    id("com.kitedi")
}

android {
    namespace = "com.example.viewmodel"
    compileSdk = 34
    defaultConfig { minSdk = 23 }
}

dependencies {
    // implementation by default — the case that slipped through.
    add(providers.gradleProperty("dataVisibility").get(), project(":data"))

    implementation("androidx.lifecycle:lifecycle-viewmodel:2.6.2")
    // The generated adapters come in Activity and Fragment flavours whether this
    // module has either or not, so both have to be resolvable.
    implementation("androidx.activity:activity:1.8.0")
    implementation("androidx.fragment:fragment:1.6.2")
}
