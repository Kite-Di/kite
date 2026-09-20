plugins {
    alias(libs.plugins.android.library)
    // Same one-plugin setup as every graph module: applies KSP, adds the Kite
    // runtime + processor + rules vocabulary, exports this module's graph fragment.
    id("com.kitedi")
}

android {
    namespace = "com.kite.demo.core.analytics"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
