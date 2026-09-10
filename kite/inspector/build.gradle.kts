plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kite.di.inspector"
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
    implementation(project(":kite:runtime"))
    implementation(project(":kite:graph-core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)

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
            artifactId = "inspector"
        }
    }
}
