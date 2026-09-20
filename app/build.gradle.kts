plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Applies KSP, adds the Kite runtime + processor (+ compose helpers, since
    // the compose plugin is applied), and configures every processor option
    // (graph export, provenance stripping, graph.rules) — nothing else needed.
    id("com.kitedi")
}

android {
    namespace = "com.kite.demo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.kite.demo"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Minified, like a real release — and the only build in which the
            // library's consumer rules actually run. check-apk-safety.sh asserts
            // that the runtime's tracing paths are gone from this APK, which is
            // only a meaningful claim under R8.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
    testOptions {
        // Unit tests run the KSP-generated factories on the JVM; android.util.Log no-ops.
        unitTests.isReturnDefaultValues = true
        // Robolectric lifecycle tests (rotation retention, fragment scopes) need resources.
        unitTests.isIncludeAndroidResources = true
    }
}

// Everything in debug, nothing in release. The debug build carries the inspector,
// which reports runtime events; the graph itself never goes to the device — the
// board reads it from build/kite/graph.json here and bridges the two.
// In release the inspector is replaced by its empty twin and R8 deletes the runtime
// tracing; scripts/check-apk-safety.sh checks that against mapping.txt.

dependencies {
    // The demo is cut like a production app — infrastructure in :core:*, vertical
    // features behind :feature:*:api/impl. Each graph module proves the
    // multi-module story: cross-module bindings, @GraphArgs union, board swimlanes.
    // (:api modules arrive transitively — the impls expose them via api().)
    implementation(project(":core:analytics"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:network"))
    implementation(project(":feature:orders:impl"))
    implementation(project(":feature:profile:impl"))

    // On-device inspector: the runtime event stream (instances created, scopes
    // opened/closed, ViewModels resolved). The board server picks it up over adb
    // by itself. Debug only — the release twin is empty bodies.
    debugImplementation(project(":kite:inspector"))
    releaseImplementation(project(":kite:inspector-noop"))

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
