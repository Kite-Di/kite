plugins {
    alias(libs.plugins.kotlin.jvm)
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

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(project(":kite:graph-core"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

apply(plugin = "maven-publish")
group = "com.kite.di"
version = "0.1.0"
java { withSourcesJar() }
configure<PublishingExtension> {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
        artifactId = "processor"
    }
}
