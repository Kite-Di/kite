plugins {
    alias(libs.plugins.kotlin.jvm)
    // No Kite plugin: an :api module is pure contract — interfaces and models
    // only, no implementations, so there is nothing to infer and no graph fragment.
    // The binding happens where the implementation lives (:feature:orders:impl).
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
