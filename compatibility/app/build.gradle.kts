import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
}

val agp8 = providers.gradleProperty("agpVersion").get().startsWith("8")

// AGP 9 carries Kotlin support itself and fails if the standalone plugin is
// applied; AGP 8 has no Kotlin without it. Applied before Kite, which brings KSP.
if (agp8) {
    apply(plugin = "org.jetbrains.kotlin.android")
}
apply(plugin = "com.kitedi")

android {
    namespace = "com.example.compat"
    // The property form, not the AGP 9 block: this file has to parse on AGP 8 too.
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.compat"
        minSdk = 23
    }

    // Nothing to do with Kite: on AGP 8 the Java tasks default to 1.8 while Kotlin
    // follows the JDK, and the build refuses to run with the two disagreeing. AGP 9
    // aligns them on its own.
    if (agp8) {
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}

if (agp8) {
    tasks.withType(KotlinCompile::class.java).configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    }
}
