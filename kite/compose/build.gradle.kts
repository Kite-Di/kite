plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kite.di.compose"
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
    api(project(":kite:runtime"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
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
            artifactId = "compose"
        }
    }
}
