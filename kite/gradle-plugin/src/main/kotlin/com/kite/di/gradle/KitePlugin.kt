package com.kite.di.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.google.devtools.ksp.gradle.KspAATask
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `id("com.kite.di")` — the whole setup.
 *
 * Applies KSP, adds the runtime + processor dependencies, and configures every
 * processor option that used to be hand-written `ksp { arg(...) }` boilerplate:
 *
 *  - `kite.module`    → the Gradle project path
 *  - `kite.rootDir`   → the root project dir (for file:line provenance)
 *  - `kite.aggregate` → "true" on `com.android.application` modules
 *  - `kite.appId`     → read from the module's `applicationId`
 *  - `kite.graphOut`  → build/kite/graph.json (dev-only artifact, never packaged)
 *  - `kite.variant` / `kite.stripProvenance` per variant — release builds
 *    strip file/line provenance strings from generated registries
 *
 * Requires the KSP plugin on the build classpath (root build.gradle.kts:
 * `alias(libs.plugins.ksp) apply false`), same as e.g. Hilt's plugin.
 */
class KitePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.apply("com.google.devtools.ksp")

        project.extensions.configure(KspExtension::class.java) { ksp ->
            ksp.arg("kite.module", project.path)
            ksp.arg("kite.rootDir", project.rootDir.absolutePath)
        }

        // Inside this repo the modules are project dependencies; consumers of the
        // published plugin get the maven artifacts of the same version.
        project.pluginManager.withPlugin("com.android.base") {
            val local = project.rootProject.findProject(":kite:runtime") != null
            if (local) {
                project.dependencies.add("implementation", project.project(":kite:runtime"))
                project.dependencies.add("ksp", project.project(":kite:processor"))
            } else {
                project.dependencies.add("implementation", "com.kite.di:runtime:$VERSION")
                project.dependencies.add("ksp", "com.kite.di:processor:$VERSION")
            }
        }

        project.pluginManager.withPlugin("com.android.application") {
            project.extensions.configure(KspExtension::class.java) { ksp ->
                ksp.arg("kite.aggregate", "true")
                ksp.arg(
                    "kite.graphOut",
                    project.layout.buildDirectory.file("kite/graph.json").get().asFile.absolutePath,
                )
            }
            // applicationId is only known after evaluation — feed it lazily.
            val appId = project.provider {
                project.extensions.getByType(ApplicationExtension::class.java)
                    .defaultConfig.applicationId ?: "unknown"
            }
            project.tasks.withType(KspAATask::class.java).configureEach { task ->
                task.kspConfig.processorOptions.put("kite.appId", appId)
            }
        }

        // Per-variant options: user-facing builds drop source-location strings.
        project.tasks.withType(KspAATask::class.java).configureEach { task ->
            val release = task.name.contains("Release")
            task.kspConfig.processorOptions.put("kite.variant", if (release) "release" else "debug")
            if (release) task.kspConfig.processorOptions.put("kite.stripProvenance", "true")
        }
    }

    private companion object {
        /** Must match the published version of :kite:runtime / :kite:processor. */
        const val VERSION = "0.1.0"
    }
}
