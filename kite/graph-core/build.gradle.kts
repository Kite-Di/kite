plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
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
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test>().configureEach {
    if (providers.gradleProperty("goldenUpdate").isPresent) {
        systemProperty("golden.update", "1")
        outputs.upToDateWhen { false }
    }
}
