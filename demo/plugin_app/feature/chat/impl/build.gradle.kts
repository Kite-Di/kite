plugins {
    id("com.android.library")
    id("com.kitedi")
}

android {
    namespace = "com.demo.feature.chat.impl"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
}

dependencies {
    api(project(":feature:chat:api"))
    implementation(project(":core:plugin"))
    implementation(project(":core:analytics"))
}
