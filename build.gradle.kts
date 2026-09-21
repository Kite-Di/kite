// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    // Applied by the :kite:* modules. It reads GROUP/VERSION_NAME/POM_* out of
    // gradle.properties, builds the sources + javadoc jars Central demands, signs
    // them, and uploads the bundle to the Central Portal — the parts plain
    // `maven-publish` does not do.
    alias(libs.plugins.maven.publish) apply false
    // Dokka fills the -javadoc.jar the JVM modules publish: the stock javadoc tool
    // reads no Kotlin, so without it Central would get an empty jar.
    alias(libs.plugins.dokka) apply false
}
