plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
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
    // Runs the real processor over source files (KSP2), for what the model-level
    // tests cannot reach: the scanner, rules, and what is generated from them.
    testImplementation(libs.kctfork.ksp)
    // @Root/@Bind/@Scoped/@Fresh for those sources.
    testImplementation(project(":kite:rules"))
    // The runtime's JVM core (Core.kt, ScopeTree.kt, …) is compiled by the harness so an
    // upstream module's generated registry compiles for real; this is what it imports.
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    // The runtime sources the harness compiles; read straight from the runtime module.
    val runtimeSources = layout.projectDirectory.dir("../runtime/src/main/kotlin/com/kite/di/runtime").asFile
    inputs.dir(runtimeSources)
    systemProperty("kite.runtimeSources", runtimeSources.absolutePath)
}
