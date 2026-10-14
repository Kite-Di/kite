plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.kite.di.test"
    compileSdk {
        version = release(34)
    }

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":kite:runtime"))
    // The rule is a JUnit 4 TestRule; the Application comes from androidx.test, which
    // instrumentation and Robolectric both provide.
    api(libs.junit)
    implementation(libs.androidx.test.core)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.fragment)
    testImplementation(libs.kotlin.test)
}

// These tests drive the runtime directly, with a hand-written registry instead of a
// generated one: they are the one place that calls Kite.init and looks up by type.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().matching { "UnitTest" in it.name }.configureEach {
    compilerOptions.optIn.addAll("com.kite.di.runtime.KiteGeneratedApi", "com.kite.di.runtime.UncheckedLookup")
}
