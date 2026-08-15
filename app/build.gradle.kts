plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Applies KSP, adds the Kite runtime + processor (+ compose helpers, since
    // the compose plugin is applied), and configures every processor option
    // (graph export, provenance stripping, graph.rules) — nothing else needed.
    id("com.kite.di")
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
            isMinifyEnabled = false
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

// The Kite plugin writes the dependency graph to build/kite/graph.json —
// a DEVELOPMENT-ONLY artifact, never packaged. No APK — debug or release — contains
// the graph, a server, or the web board; see scripts/check-apk-safety.sh. View it
// on the host with `cd webboard && npm run board`.

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
