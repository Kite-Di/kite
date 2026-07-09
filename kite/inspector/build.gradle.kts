plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kite.di.inspector"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":kite:runtime"))
    implementation(project(":kite:graph-core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

android {
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}
apply(plugin = "maven-publish")
group = "com.kite.di"
version = "0.1.0"
afterEvaluate {
    configure<PublishingExtension> {
        publications.create<MavenPublication>("release") {
            from(components["release"])
            artifactId = "inspector"
        }
    }
}

// --- web board bundling ----------------------------------------
// The board is built with npm and served from this AAR's assets, so a debug build
// of a consuming app needs zero frontend tooling at runtime. Skip with -PskipWebboard.
val webboardDir = rootProject.layout.projectDirectory.dir("webboard")
val webboardAssets = layout.buildDirectory.dir("generated/webboardAssets")

val buildWebboard = tasks.register<Exec>("buildWebboard") {
    description = "Builds the web board bundle with npm (skip with -PskipWebboard)."
    workingDir(webboardDir)
    commandLine("sh", "-c", "npm install --no-audit --no-fund --loglevel=error && npm run build")
    inputs.files(
        fileTree(webboardDir) {
            include("src/**", "index.html", "package.json", "vite.config.ts", "tsconfig.json")
        }
    )
    outputs.dir(webboardDir.dir("dist"))
    onlyIf {
        val enabled = !providers.gradleProperty("skipWebboard").isPresent &&
            webboardDir.file("package.json").asFile.exists()
        if (!enabled) logger.lifecycle("buildWebboard skipped (-PskipWebboard or webboard/ absent)")
        enabled
    }
}

val bundleWebboard = tasks.register<Sync>("bundleWebboard") {
    dependsOn(buildWebboard)
    from(webboardDir.dir("dist"))
    into(webboardAssets.map { it.dir("webboard") })
}

extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
    // Plain path (not a Provider): AGP 9 forbids lazy providers here; the task
    // dependency is carried explicitly by preBuild below.
    sourceSets.getByName("main").assets.srcDir("build/generated/webboardAssets")
}
tasks.named("preBuild") { dependsOn(bundleWebboard) }
