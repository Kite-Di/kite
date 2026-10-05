package com.kite.di.gradle

import java.io.File

/** An Android app module with test fixtures — the KSP task neither old name check knew about. */
internal object AndroidFixture {

    val sdkDir: String = System.getProperty("kite.sdkDir").orEmpty().also {
        check(it.isNotEmpty()) { "No Android SDK: set ANDROID_HOME or sdk.dir in local.properties" }
    }

    /** The KSP tasks AGP gives such a module, and which of them compile the app's own sources. */
    val mainTasks = listOf("kspDebugKotlin", "kspReleaseKotlin")
    val otherTasks = listOf(
        "kspDebugUnitTestKotlin", // AGP 9 creates unit tests for the tested build type only
        "kspDebugAndroidTestKotlin",
        "kspDebugTestFixturesKotlin", "kspReleaseTestFixturesKotlin",
    )

    /** A one-module build at [root] for TestKit, its app module carrying [appScript] below the setup. */
    fun writeBuild(root: File, appScript: String = "") {
        root.resolve("local.properties").writeText("sdk.dir=$sdkDir\n")
        root.resolve("gradle.properties").writeText(
            """
            org.gradle.jvmargs=-Xmx2048m
            android.useAndroidX=true
            kite.startBoard=false
            """.trimIndent(),
        )
        root.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
            }
            rootProject.name = "fixture"
            include(":app")
            """.trimIndent(),
        )
        File(root, "app").mkdirs()
        root.resolve("app/build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("com.kitedi")
            }

            android {
                namespace = "com.example.fixture"
                compileSdk = 34
                defaultConfig { minSdk = 23 }
                testFixtures { enable = true }
            }

            // The wiring is under test, not the artifacts: the plugin asks for this
            // version's runtime and processor, which need not be published anywhere yet.
            configurations.configureEach { exclude(group = "com.kitedi") }

            """.trimIndent() + appScript,
        )
    }
}
