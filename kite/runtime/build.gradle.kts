plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kite.di.runtime"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
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
    api(project(":kite:annotations"))
    api(project(":kite:graph-core"))
    implementation(libs.kotlinx.coroutines.core)
    // androidx integrations are optional: classes referencing them load only when
    // the app uses them (Activity/Fragment scopes, `by injected()`, `by injectedViewModel()`).
    compileOnly(libs.androidx.fragment)
    compileOnly(libs.androidx.activity)
    compileOnly(libs.androidx.lifecycle.viewmodel)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

android {
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}
apply(plugin = "maven-publish")
group = "com.kite.di"
version = "0.1.0"
afterEvaluate {
    configure<PublishingExtension> {
        publications.create<MavenPublication>("release") {
            from(components["release"])
            artifactId = "runtime"
        }
    }
}
