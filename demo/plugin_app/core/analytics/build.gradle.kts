plugins {
    id("com.android.library")
    id("com.kitedi")
}

android {
    namespace = "com.demo.core.analytics"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
}

dependencies {
    api(project(":core:plugin"))
}
