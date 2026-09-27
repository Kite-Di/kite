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
    namespace = "com.example.dynamic"
    // The property form, not the AGP 9 block: this file has to parse on AGP 8 too.
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.dynamic"
        minSdk = 23
        versionCode = 1
        versionName = "1.0"
    }

    // The point of the fixture: with a dynamic feature declared, AGP refuses every
    // external compileOnly dependency on this module — including Kite's rules
    // vocabulary, which is compileOnly everywhere else.
    dynamicFeatures += setOf(":feature")

    buildTypes {
        release {
            // Minified on purpose: check-apk-safety.sh reads mapping.txt to prove the
            // rules annotations are gone, and refuses to pass an unminified release.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

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
