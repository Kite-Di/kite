plugins {
    id("com.android.library")
    id("com.kitedi")
}

android {
    namespace = "com.demo.feature.orders.impl"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
}

dependencies {
    api(project(":feature:orders:api"))
    implementation(project(":core:plugin"))
    implementation(project(":core:network"))
}
