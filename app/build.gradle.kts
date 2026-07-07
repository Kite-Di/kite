plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
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
    packaging {
        resources {
            // Ktor/coroutines (debug-only inspector) ship overlapping META-INF entries.
            excludes += "META-INF/{AL2.0,LGPL2.1,INDEX.LIST,io.netty.versions.properties}"
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // The compile-time graph carries file/line provenance — debug-only artifact.
        // (Variant API, not buildTypes.release.packaging: that resolves against the
        // outer android{} receiver in kts and would exclude it from debug too.)
        variant.packaging.resources.excludes.add("kite/graph.json")
    }
}

ksp {
    arg("kite.module", project.path)
    arg("kite.rootDir", rootProject.projectDir.absolutePath)
    arg("kite.aggregate", "true")
    arg("kite.appId", "com.kite.demo")
    arg("kite.variant", "debug")
}

dependencies {
    implementation(project(":kite:runtime"))
    ksp(project(":kite:processor"))
    debugImplementation(project(":kite:inspector"))
    releaseImplementation(project(":kite:inspector-noop"))

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
