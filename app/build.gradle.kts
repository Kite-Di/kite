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
}

// The dependency graph is a DEVELOPMENT-ONLY artifact: the processor writes it to
// build/kite/graph.json (below), which is never packaged. No APK — debug or
// release — contains the graph, a server, or the web board; see
// scripts/check-apk-safety.sh. View it on the host with `cd webboard && npm run board`.
ksp {
    arg("kite.module", project.path)
    arg("kite.rootDir", rootProject.projectDir.absolutePath)
    arg("kite.aggregate", "true")
    arg("kite.appId", "com.kite.demo")
    arg("kite.graphOut", layout.buildDirectory.file("kite/graph.json").get().asFile.absolutePath)
}

// Per-variant processor options: user-facing builds additionally drop the file/line
// provenance strings from the generated registries.
tasks.withType<com.google.devtools.ksp.gradle.KspAATask>().configureEach {
    val isRelease = name.contains("Release")
    kspConfig.processorOptions.put("kite.variant", if (isRelease) "release" else "debug")
    if (isRelease) {
        kspConfig.processorOptions.put("kite.stripProvenance", "true")
    }
}

dependencies {
    implementation(project(":kite:runtime"))
    ksp(project(":kite:processor"))

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
