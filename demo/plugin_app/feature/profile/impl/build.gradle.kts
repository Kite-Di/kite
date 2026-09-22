plugins {
    id("com.android.library")
    id("com.kitedi")
}

android {
    namespace = "com.demo.feature.profile.impl"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
}

dependencies {
    api(project(":feature:profile:api"))
    implementation(project(":core:plugin"))
    implementation(project(":core:network"))
}
