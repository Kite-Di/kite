plugins {
    id("com.android.application")
    id("com.kitedi")
}

android {
    namespace = "com.example.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.app"
        minSdk = 23
    }
}

dependencies {
    implementation(project(":mid"))
}
