plugins {
    alias(libs.plugins.android.application)
    // Applies KSP, adds the Kite runtime + processor, and configures every
    // processor option (graph export, provenance stripping) — nothing else needed.
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
    }
    testOptions {
        // Unit tests run the KSP-generated factories on the JVM; android.util.Log no-ops.
        unitTests.isReturnDefaultValues = true
    }
}

// The Kite plugin writes the dependency graph to build/kite/graph.json —
// a DEVELOPMENT-ONLY artifact, never packaged. No APK — debug or release — contains
// the graph, a server, or the web board; see scripts/check-apk-safety.sh. View it
// on the host with `cd webboard && npm run board`.

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
