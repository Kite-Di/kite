plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // No Kite plugin: a design system is pure UI vocabulary — nothing here
    // is injectable, so this module has no graph fragment and no GraphRules.kt.
}

android {
    namespace = "com.kite.demo.core.designsystem"
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
}
