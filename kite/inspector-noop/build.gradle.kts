plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kite.di.inspector.noop"
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
            artifactId = "inspector-noop"
        }
    }
}
