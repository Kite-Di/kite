plugins {
    `java-gradle-plugin`
    // Not `kotlin-dsl`: Gradle's embedded Kotlin (2.2.x) cannot read the Kotlin 2.3
    // metadata of the KSP Gradle plugin we compile against — use the repo's Kotlin.
    alias(libs.plugins.kotlin.jvm)
    // Inside this repo the plugin is consumed through includeBuild and needs no
    // coordinates. A consumer outside it has only the marker to go by, so the
    // release has to publish one — `java-gradle-plugin` generates the marker
    // publication, but only once maven-publish is here to carry it.
    `maven-publish`
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
