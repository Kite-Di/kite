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
    // The contracts and their aggregators, and the shared infrastructure the
    // features are built on — an application lists what it is assembled from.
    implementation(project(":core:plugin"))
    implementation(project(":core:network"))
    implementation(project(":core:analytics"))

    // The features. This list is the only place they are named — no code in :app
    // references any of them, and the graph picks up what they contribute.
    implementation(project(":feature:orders:impl"))
    implementation(project(":feature:profile:impl"))
    implementation(project(":feature:chat:impl"))
}

dependencies { testImplementation("junit:junit:4.13.2") }
