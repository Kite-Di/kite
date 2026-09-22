plugins {
    id("com.android.application")
    id("com.kitedi")
}

android {
    namespace = "com.demo.pluginapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.demo.pluginapp"
        minSdk = 23
    }
}

dependencies {
    // The contracts and their aggregators.
    implementation(project(":core:plugin"))

    // The features. This list is the only place they are named — no code in :app
    // references any of them, and the graph picks up what they contribute.
    implementation(project(":feature:orders:impl"))
    implementation(project(":feature:profile:impl"))
    implementation(project(":feature:chat:impl"))
}
