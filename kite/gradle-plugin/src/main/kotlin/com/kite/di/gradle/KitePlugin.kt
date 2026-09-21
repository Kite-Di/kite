package com.kite.di.gradle

import com.google.devtools.ksp.gradle.KspAATask
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * `id("com.kitedi")` — the whole setup.
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
 * On application modules it also registers `kiteBoard`, a finalizer of the
 * debug KSP task: every debug build prints the host-side dependency-board link
 * (`http://localhost:8394`) and, unless something is already serving it, starts
 * the board server detached so the link works. Dev-only, never in an APK.
 * Gradle properties: `-Pkite.boardPort=NNNN`, `-Pkite.startBoard=false`
 * (default: auto-start locally, off under CI).
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
            // Pending decisions for the board's cards — a dev-only
            // build artifact next to graph.json, never packaged.
            ksp.arg(
                "kite.decisionsOut",
                project.layout.buildDirectory.file("kite/decisions.json").get().asFile.absolutePath,
            )
            // Every module writes its graph (the app module the full graph, library
            // modules their fragment) — the board server merges them into one canvas.
            // Dev-only build artifacts, never packaged.
            ksp.arg(
                "kite.graphOut",
                project.layout.buildDirectory.file("kite/graph.json").get().asFile.absolutePath,
            )
        }

        // Inside this repo the modules are project dependencies; consumers of the
        // published plugin get the maven artifacts of the same version.
        project.pluginManager.withPlugin("com.android.base") {
            val local = project.rootProject.findProject(":kite:runtime") != null
            if (local) {
                project.dependencies.add("implementation", project.project(":kite:runtime"))
                project.dependencies.add("ksp", project.project(":kite:processor"))
                // The decisions vocabulary (@Root/@Bind/@Scoped):
                // SOURCE retention + compileOnly = never in any APK, by construction.
                project.dependencies.add("compileOnly", project.project(":kite:rules"))
            } else {
                project.dependencies.add("implementation", "$KITE_GROUP:runtime:$KITE_VERSION")
                project.dependencies.add("ksp", "$KITE_GROUP:processor:$KITE_VERSION")
                project.dependencies.add("compileOnly", "$KITE_GROUP:rules:$KITE_VERSION")
            }
        }

        // Compose detected → ViewModels also get @Composable remember adapters, and
        // the composable helpers (`injected()`, injectedViewModel) come on the classpath.
        project.pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
            project.extensions.configure(KspExtension::class.java) { ksp ->
                ksp.arg("kite.compose", "true")
            }
            val local = project.rootProject.findProject(":kite:compose") != null
            if (local) {
                project.dependencies.add("implementation", project.project(":kite:compose"))
            } else {
                project.dependencies.add("implementation", "$KITE_GROUP:compose:$KITE_VERSION")
            }
        }

        project.pluginManager.withPlugin("com.android.application") {
            project.extensions.configure(KspExtension::class.java) { ksp ->
                ksp.arg("kite.aggregate", "true")
            }
            // applicationId is only known after evaluation, so it has to be lazy, and
            // what KSP finally reads must be a plain String: KSP resolves its options
            // inside a worker, and anything touching AGP there is a linkage error
            // rather than a build error.
            //
            // Read reflectively on purpose. AGP 9 dropped the type parameters from
            // CommonExtension, so `ApplicationExtension.getDefaultConfig()` compiles to
            // a different descriptor than AGP 8 exposes, and a typed call linked
            // against one fails on the other. One string is not worth being pinned to
            // a single AGP major for.
            val appId = project.objects.property(String::class.java).convention("unknown")
            project.afterEvaluate { evaluated ->
                readApplicationId(evaluated)?.let(appId::set)
            }
            project.tasks.withType(KspAATask::class.java).configureEach { task ->
                task.kspConfig.processorOptions.put("kite.appId", appId)
            }

            // Every debug build rewrites graph.json — end it by surfacing the
            // host-side board link (dev-only; the board never ships in an APK).
            // Finalizes the debug KSP task so the link prints even on a no-op
            // rebuild. Props: `-Pkite.boardPort=NNNN`, `-Pkite.startBoard=false`.
            val board = registerBoardTask(project)
            project.tasks.withType(KspAATask::class.java).configureEach { task ->
                if (task.name.contains("Debug") &&
                    !task.name.contains("UnitTest") && !task.name.contains("AndroidTest")
                ) {
                    task.finalizedBy(board)
                }
            }
        }

        // Per-variant options: user-facing builds drop source-location strings.
        // Test compilations skip inference entirely — running it over test sources
        // would generate a second registry shadowing the main graph.
        project.tasks.withType(KspAATask::class.java).configureEach { task ->
            val release = task.name.contains("Release")
            task.kspConfig.processorOptions.put("kite.variant", if (release) "release" else "debug")
            if (release) task.kspConfig.processorOptions.put("kite.stripProvenance", "true")
            if (task.name.contains("UnitTest") || task.name.contains("AndroidTest")) {
                task.kspConfig.processorOptions.put("kite.skip", "true")
            }
            // Decisions are @Root/@Bind/@Scoped annotations in module sources
            // — ordinary source edits, no extra task inputs needed.
        }
    }

    /**
     * `android.defaultConfig.applicationId`, read without linking against either
     * AGP major. Null when the DSL does not expose it — the board then labels the
     * app "unknown", which is cosmetic.
     */
    private fun readApplicationId(project: Project): String? = runCatching {
        val android = project.extensions.findByName("android") ?: return null
        val defaultConfig = android.javaClass.getMethod("getDefaultConfig").invoke(android)
        defaultConfig.javaClass.getMethod("getApplicationId").invoke(defaultConfig) as? String
    }.getOrNull()

    /**
     * A never-up-to-date task that prints the board link after a debug build and,
     * unless one is already listening, starts the board server detached. All paths
     * are resolved at configuration time so the action captures no `Project`.
     */
    private fun registerBoardTask(project: Project): TaskProvider<*> {
        // `providers.gradleProperty`, not `findProperty`: the latter searches parent
        // projects, which isolated projects forbids.
        val port = project.providers.gradleProperty("kite.boardPort").orNull?.toIntOrNull()
            ?: DEFAULT_BOARD_PORT
        val repoRoot = project.rootDir
        val startPreference = project.providers.gradleProperty("kite.startBoard").orNull
        // Inside Kite's own build the board is a project; for a consumer it is the
        // artifact published alongside this plugin. Either way it is resolved here,
        // not put on the plugin's classpath — the plugin never calls into it, it
        // spawns it.
        val boardDependency = if (project.rootProject.findProject(":kite:board") != null) {
            project.dependencies.project(mapOf("path" to ":kite:board"))
        } else {
            project.dependencies.create("$KITE_GROUP:board:$KITE_VERSION")
        }
        // A FileCollection, not the Configuration itself: the task action needs the
        // paths, and a Configuration cannot be serialized into the configuration cache.
        val boardClasspath = project.files(
            project.configurations.detachedConfiguration(boardDependency)
        )
        val kiteDir = project.layout.buildDirectory.dir("kite")
        // Only auto-start on a genuine build/install/run — not when kspDebugKotlin
        // is dragged in by `test`/`check`/`compile`. The link still prints everywhere.
        val buildLike = project.gradle.startParameter.taskNames.any { requested ->
            val name = requested.substringAfterLast(':').lowercase()
            name.startsWith("assemble") || name.startsWith("install") || name.startsWith("bundle") ||
                name.startsWith("run") || name == "build" || name == "kiteboard"
        }
        // Decided here, not in the action: when the board will not start, the task
        // must not depend on the classpath at all, or every debug build downloads
        // the board jar for nothing.
        val ci = project.providers.environmentVariable("CI").isPresent
        val autostart = when (startPreference?.lowercase()) {
            "true", "1", "yes", "on" -> true // explicit opt-in wins everywhere
            "false", "0", "no", "off" -> false
            else -> !ci && buildLike // default: local build/install/run invocations only
        }
        return project.tasks.register("kiteBoard") { task ->
            task.group = "kite"
            task.description = "Prints the dependency-board link; starts the board server if nothing is serving it yet."
            task.outputs.upToDateWhen { false } // always announce, even on a no-op rebuild
            val graphFile = kiteDir.get().file("graph.json").asFile
            val decisionsFile = kiteDir.get().file("decisions.json").asFile
            val logFile = kiteDir.get().file("board-server.log").asFile
            // Library modules' graph fragments (multi-module apps): the board server
            // watches and merges them. Resolved here, at task realization — the
            // dependency graph is fully declared by then, and doLast captures no Project.
            val fragmentFiles = projectDependencyFragments(project)
            // Only when the board may actually start: this both builds the board (when
            // it is a project of this build) and downloads it (when it is an artifact).
            if (autostart) task.dependsOn(boardClasspath)
            task.doLast {
                BoardLink.announce(
                    logger = task.logger,
                    port = port,
                    graphFile = graphFile,
                    fragmentFiles = fragmentFiles,
                    decisionsFile = decisionsFile,
                    repoRoot = repoRoot,
                    logFile = logFile,
                    autostart = autostart,
                    boardClasspath = if (autostart) boardClasspath.map { it.absolutePath } else emptyList(),
                )
            }
        }
    }

    /**
     * `build/kite/graph.json` of every project dependency reachable from this
     * module over `implementation`/`api` edges — each one a graph fragment the
     * board server merges into the app's canvas.
     */
    private fun projectDependencyFragments(project: Project): List<File> {
        val visited = linkedSetOf<String>()
        fun visit(p: Project) {
            for (configuration in p.configurations) {
                if (configuration.name != "implementation" && configuration.name != "api") continue
                for (dependency in configuration.dependencies) {
                    if (dependency !is ProjectDependency) continue
                    val path = dependency.path
                    if (path != p.path && visited.add(path)) visit(p.rootProject.project(path))
                }
            }
        }
        visit(project)
        return visited.map { path ->
            project.rootProject.project(path).layout.buildDirectory.file("kite/graph.json").get().asFile
        }
    }

    private companion object {
        /** Board server default. Override: `-Pkite.boardPort=`. */
        const val DEFAULT_BOARD_PORT = 8394
    }
}

/**
 * The post-build board announcement: probe the port, print a clickable link, and
 * (by default, off under CI) start the board server detached so the link works.
 * Pure host-side dev sugar — it touches nothing that reaches an APK, and any
 * failure is swallowed so it can never break a build.
 */
private object BoardLink {

    /** Entry point of the board process (`:kite:board`), spawned detached. */
    private const val BOARD_MAIN = "com.kite.di.board.BoardMain"

    fun announce(
        logger: Logger,
        port: Int,
        graphFile: File,
        fragmentFiles: List<File>,
        decisionsFile: File,
        repoRoot: File,
        logFile: File,
        /** Decided at configuration time — see registerBoardTask. */
        autostart: Boolean,
        boardClasspath: List<String>,
    ) {
        val url = "http://localhost:$port"
        if (isUp(port)) {
            logger.lifecycle("\n  Dependency board (live): $url\n")
            return
        }

        if (autostart && start(boardClasspath, graphFile, fragmentFiles, decisionsFile, repoRoot, port, logFile)) {
            logger.lifecycle("\n  Dependency board: $url  (starting)")
            logger.lifecycle("  server log → $logFile\n")
            return
        }

        logger.lifecycle("\n  Dependency board: $url")
        logger.lifecycle("  ↳ not serving yet — run:  ./gradlew kiteBoard")
        logger.lifecycle("")
    }

    /** Something already listening on the board port? A plain TCP connect is enough. */
    private fun isUp(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Spawns the board as its own JVM process, detached: it has to outlive the build
     * that started it, so the page stays open and picks up the next one.
     *
     * The classpath is assembled from the jars this plugin is already running on —
     * the board, the graph model, the serialization runtime and the Kotlin stdlib —
     * so there is nothing to resolve and nothing for the consumer to install. Output
     * goes to a log file; a full pipe would block the process.
     */
    private fun start(
        boardClasspath: List<String>,
        graphFile: File,
        fragmentFiles: List<File>,
        decisionsFile: File,
        repoRoot: File,
        port: Int,
        logFile: File,
    ): Boolean = try {
        logFile.parentFile?.mkdirs()
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        if (boardClasspath.isEmpty()) {
            false
        } else {
            ProcessBuilder(
                java,
                "-cp", boardClasspath.joinToString(File.pathSeparator),
                BOARD_MAIN,
                "--port=$port",
                "--graph=${graphFile.absolutePath}",
                "--fragments=${fragmentFiles.joinToString(File.pathSeparator) { it.absolutePath }}",
                "--decisions=${decisionsFile.absolutePath}",
                "--repo=${repoRoot.absolutePath}",
            )
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
            true
        }
    } catch (_: Exception) {
        false
    }

}
