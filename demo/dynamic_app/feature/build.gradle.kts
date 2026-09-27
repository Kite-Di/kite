// Deliberately empty of Kotlin and of Kite: the fixture is about what declaring a
// dynamic feature does to the *base* module's dependencies.
plugins {
    id("com.android.dynamic-feature")
}

android {
    namespace = "com.example.dynamic.feature"
    compileSdk = 34
    defaultConfig {
        minSdk = 23
    }
}

dependencies {
    implementation(project(":app"))
}
