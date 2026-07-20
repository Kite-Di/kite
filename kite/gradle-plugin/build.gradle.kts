plugins {
    `java-gradle-plugin`
    // Not `kotlin-dsl`: Gradle's embedded Kotlin (2.2.x) cannot read the Kotlin 2.3
    // metadata of the KSP Gradle plugin we compile against — use the repo's Kotlin.
    alias(libs.plugins.kotlin.jvm)
}

group = "com.kite.di"
version = "0.1.0"

dependencies {
    // compileOnly: both are already on the consuming build's classpath (the root
    // build declares them `apply false`), so we must not ship a second copy.
    compileOnly("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
}

gradlePlugin {
    plugins {
        create("kite") {
            id = "com.kite.di"
            implementationClass = "com.kite.di.gradle.KitePlugin"
        }
    }
}
