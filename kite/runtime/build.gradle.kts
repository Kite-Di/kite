plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.kite.di.runtime"
    compileSdk {
        version = release(34)
    }

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":kite:graph-core"))
    implementation(libs.kotlinx.coroutines.core)
    // androidx integrations are optional: classes referencing them load only when
    // the app uses them (Activity/Fragment scopes, `by injected()`, generated ViewModel adapters).
    compileOnly(libs.androidx.fragment)
    compileOnly(libs.androidx.activity)
    compileOnly(libs.androidx.lifecycle.viewmodel)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
