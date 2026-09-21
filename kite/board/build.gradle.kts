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
    api(project(":kite:graph-core"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

// The board UI is a Vite bundle; it rides inside this jar so the Gradle plugin can
// serve it with no npm, no node and no checkout of this repository.
// Relative to this project, not via rootProject: reaching into another project's
// layout is what isolated projects forbids.
val webboardDir = layout.projectDirectory.dir("../../webboard")
val bundleDir = layout.buildDirectory.dir("generated/boardBundle")

val buildWebboard = tasks.register<Exec>("buildWebboard") {
    description = "Builds the board UI bundle with npm (skip with -PskipWebboard)."
    workingDir(webboardDir)
    commandLine("sh", "-c", "npm install --no-audit --no-fund --loglevel=error && npm run build")
    inputs.files(
        fileTree(webboardDir) {
            include("src/**", "index.html", "package.json", "vite.config.ts", "tsconfig.json")
        }
    )
    outputs.dir(webboardDir.dir("dist"))
    // Both read here, not inside the spec: a lambda that reaches back into the
    // build script cannot be stored in the configuration cache.
    val skipRequested = providers.gradleProperty("skipWebboard").isPresent
    val packageJson = webboardDir.file("package.json").asFile
    onlyIf { task ->
        val enabled = !skipRequested && packageJson.exists()
        if (!enabled) task.logger.lifecycle("buildWebboard skipped (-PskipWebboard or webboard/ absent)")
        enabled
    }
}

val bundleWebboard = tasks.register<Sync>("bundleWebboard") {
    dependsOn(buildWebboard)
    from(webboardDir.dir("dist"))
    into(bundleDir.map { it.dir("board") })
}

sourceSets.named("main") { resources.srcDir(bundleDir) }
// The bundle is a generated resource directory, so every task that reads the main
// source set has to wait for it — the jar and, since we publish one, the sources jar.
tasks.named("processResources") { dependsOn(bundleWebboard) }
// `matching`, not `named`: the publishing plugin registers sourcesJar after this
// script has been evaluated.
tasks.matching { it.name == "sourcesJar" }.configureEach { dependsOn(bundleWebboard) }
