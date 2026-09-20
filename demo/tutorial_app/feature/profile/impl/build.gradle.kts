plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // Applies KSP + runtime/processor/rules and exports this module's graph
    // fragment. Compose is detected → ProfileViewModel also gets a generated
    // `rememberProfileViewModel()` adapter.
    id("com.kitedi")
}

android {
    namespace = "com.kite.demo.feature.profile.impl"
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
    // The contract this module implements — api(): consumers of :impl see the
    // interfaces without declaring :api themselves.
    api(project(":demo:tutorial_app:feature:profile:api"))

    implementation(project(":demo:tutorial_app:core:analytics"))
    implementation(project(":demo:tutorial_app:core:designsystem"))
    implementation(project(":demo:tutorial_app:core:network"))

    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
}
