package com.kite.di.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.google.devtools.ksp.gradle.KspAATask
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Provider
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

    /** `ksp<Variant>Kotlin` for each of this module's variants — the only KSP tasks that infer. */
    private val mainKspTasks = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Whether the KSP task [taskName] compiles a variant's own sources — the only ones
     * that infer. Complete only once the variants are known, so callers ask lazily.
     */
    internal fun infers(project: Project, taskName: String): Boolean =
        if (project.plugins.hasPlugin("com.android.base")) taskName in mainKspTasks else taskName == "kspKotlin"

    /** The KSP tasks of variants users get: the release build type, or anything minified. */
    private val userFacingKspTasks = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Whether [taskName] compiles a build that reaches users — no provenance strings,
     * no graph, no board. Decided from the variant, not its name: a minified
     * `staging` build type is as user-facing as `release`.
     */
    internal fun userFacing(taskName: String): Boolean = taskName in userFacingKspTasks

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
            } else {
                project.dependencies.add("implementation", "$KITE_GROUP:runtime:$KITE_VERSION")
                project.dependencies.add("ksp", "$KITE_GROUP:processor:$KITE_VERSION")
            }
            // KiteTestRule + replace(): test classpaths only, never the app's.
            val testSupport: Any = if (local) project.project(":kite:test") else "$KITE_GROUP:test:$KITE_VERSION"
            // Only where they exist: a com.android.test module has no unit tests of its own.
            project.configurations
                .matching { it.name == "testImplementation" || it.name == "androidTestImplementation" }
                .configureEach { project.dependencies.add(it.name, testSupport) }
            addRulesVocabulary(project, local)
            addHostTypes(project)

            // The KSP tasks that compile a variant's own sources. Every other one —
            // unit tests, androidTest, test fixtures, screenshot tests, whatever AGP
            // adds next — skips inference (see below).
            project.extensions
                .getByType(com.android.build.api.variant.AndroidComponentsExtension::class.java)
                .onVariants { variant ->
                    val kspTask = "ksp${variant.name.replaceFirstChar(Char::uppercaseChar)}Kotlin"
                    mainKspTasks += kspTask
                    val minified = (variant as? com.android.build.api.variant.CanMinifyCode)?.isMinifyEnabled == true
                    if (variant.buildType == "release" || minified) userFacingKspTasks += kspTask
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
            // The board matches this against what the app reports over adb, so it has
            // to be the id the variant actually installs — `defaultConfig.applicationId`
            // misses applicationIdSuffix, which most debug builds set.
            //
            // Taken from the variant API rather than the DSL: AGP 9 dropped the type
            // parameters from CommonExtension, so a typed call on ApplicationExtension
            // links against one AGP major and fails on the other. `ApplicationVariant`
            // is stable across both. The value is read here, while variants are being
            // configured, because KSP resolves its options inside a worker where
            // touching AGP at all would be a linkage error.
            project.extensions
                .getByType(ApplicationAndroidComponentsExtension::class.java)
                .onVariants { variant ->
                    val appId = variant.applicationId.get()
                    val kspTask = "ksp${variant.name.replaceFirstChar(Char::uppercaseChar)}Kotlin"
                    project.tasks.withType(KspAATask::class.java).configureEach { task ->
                        if (task.name == kspTask) {
                            task.kspConfig.processorOptions.put("kite.appId", appId)
                            // Modules whose graph ships in this app without being merged.
                            // The runtime side has to be built before it can be read, so
                            // the task says so rather than failing on a half-built graph.
                            val runtime = projectArtifacts(project, variant.name + "RuntimeClasspath")
                            task.dependsOn(runtime.files)
                            task.kspConfig.processorOptions.put(
                                "kite.unmergedModules",
                                unmergedModules(projectArtifacts(project, variant.name + "CompileClasspath"), runtime),
                            )
                        }
                    }
                }

            // Every debug build rewrites graph.json — end it by surfacing the
            // host-side board link (dev-only; the board never ships in an APK).
            // Finalizes the debug KSP task so the link prints even on a no-op
            // rebuild. Props: `-Pkite.boardPort=NNNN`, `-Pkite.startBoard=false`.
            val board = registerBoardTask(project)
            registerBoardStopTask(project)
            // Only a debug variant's own KSP task — the one that writes graph.json. Lazy,
            // like `kite.skip` below: the variant set is complete once dependencies resolve.
            project.tasks.withType(KspAATask::class.java).configureEach { task ->
                val name = task.name
                task.finalizedBy(
                    java.util.concurrent.Callable {
                        if (infers(project, name) && !userFacing(name)) listOf(board) else emptyList()
                    },
                )
            }
        }

        // Per-variant options: user-facing builds drop source-location strings.
        // Only a variant's own sources are inferred: running inference over test or
        // fixture sources would generate a second registry shadowing the main graph
        // (test fixtures do not even have the runtime to compile it against). Decided
        // by what the task compiles, not by its name, and lazily — the variant set is
        // complete by the time a task runs.
        project.tasks.withType(KspAATask::class.java).configureEach { task ->
            val name = task.name
            task.kspConfig.processorOptions.put(
                "kite.variant",
                project.provider { if (userFacing(name)) "release" else "debug" },
            )
            task.kspConfig.processorOptions.put(
                "kite.stripProvenance",
                project.provider { userFacing(name).toString() },
            )
            task.kspConfig.processorOptions.put(
                "kite.skip",
                project.provider { (!infers(project, name)).toString() },
            )
            // Which interfaces the processor may bind to is the build's answer, not a
            // question about package names.
            ownedClasspath(project, task.name)?.let { owned ->
                task.kspConfig.processorOptions.put("kite.projectClasspath", owned)
            }
            // Decisions are @Root/@Bind/@Scoped annotations in module sources
            // — ordinary source edits, no extra task inputs needed.
        }
    }

    /**
     * Modules of this build whose graph is in the application at runtime but not in its
     * merge — an inconsistency the build has to refuse.
     *
     * `implementation` is not transitive, so a module reached only through another
     * module's `implementation` is on the application's runtime classpath and off its
     * compile classpath. Its classes are in the APK; its registry is nowhere, so its
     * bindings are registered nowhere. Whatever resolves from it fails on the device —
     * and unlike a constructor parameter, a `Kite.get<T>()` or `by injected()` inside a
     * function body is something KSP cannot see, so no amount of exported metadata from
     * the module itself would reveal the demand. What the build *can* see is that the
     * module is there and unmerged, which is enough: a graph module that ships is part of
     * the graph.
     *
     * Only modules that actually carry a registry count — a plain library reached the
     * same way is nobody's business.
     */
    private fun unmergedModules(compile: ProjectArtifacts, runtime: ProjectArtifacts): Provider<String> =
        runtime.entries.zip(compile.entries) { runtimeEntries, compileEntries ->
            val merged = compileEntries.map { it.first }.toSet()
            runtimeEntries
                .filter { (path, _) -> path !in merged }
                .filter { (_, file) -> carriesRegistry(file) }
                .map { it.first }
                .distinct()
                .sorted()
                .joinToString(",")
        }

    /** A configuration's project-owned artifacts: the paths to read, and the files to await. */
    private class ProjectArtifacts(val entries: Provider<List<Pair<String, File>>>, val files: FileCollection)

    private fun projectArtifacts(project: Project, configurationName: String): ProjectArtifacts {
        val configuration = project.configurations.findByName(configurationName)
            ?: return ProjectArtifacts(project.provider { emptyList() }, project.files())
        val view = configuration.incoming.artifactView { v ->
            v.attributes { it.attribute(ARTIFACT_TYPE, "android-classes-jar") }
            v.componentFilter { it is ProjectComponentIdentifier }
            v.isLenient = true
        }
        return ProjectArtifacts(
            entries = view.artifacts.resolvedArtifacts.map { artifacts ->
                artifacts.map { (it.id.componentIdentifier as ProjectComponentIdentifier).projectPath to it.file }
            },
            files = view.files,
        )
    }

    /** Does this artifact hold a Kite registry — i.e. does that module have a graph? */
    private fun carriesRegistry(file: File): Boolean = runCatching {
        when {
            file.isDirectory -> file.walkTopDown().any { it.isFile && it.name.endsWith(REGISTRY_SUFFIX) }
            file.isFile -> java.util.zip.ZipFile(file).use { zip ->
                zip.entries().asSequence().any { it.name.endsWith(REGISTRY_SUFFIX) }
            }
            else -> false
        }
    }.getOrDefault(false)

    /**
     * The compile-classpath entries of *this build's own modules*, for the variant
     * [kspTaskName] compiles.
     *
     * This is what tells the processor which interfaces are the project's own. A
     * package name cannot: `com.google.samples.*` is an application and
     * `com.google.android.material.*` is a library. Gradle already labels every
     * classpath entry, and the ones from this build carry a
     * `ProjectComponentIdentifier` — so the answer is a filter, not a heuristic.
     *
     * Kite's own modules are dropped: inside this repository they are project
     * dependencies, and treating `com.kite.di.runtime` as project-owned would make a
     * class implementing `Provider` bind to it.
     *
     * The variant is read out of the task name (`kspDebugKotlin` → `debug`), the same
     * way the options above are decided. Nothing here can break a build: an
     * unexpected name yields no configuration, the option is omitted, and the
     * processor falls back to its pre-classpath behaviour.
     *
     * `artifactType` has to be named: an Android library publishes its compile
     * artifacts as several sub-variants (`jar`, `r-class-jar`,
     * `android-symbol-with-package-name`) and without the attribute the selection is
     * ambiguous. `android-classes-jar` is the one AGP itself asks for when it builds
     * a compile classpath, and a plain JVM module reaches it through AGP's own
     * `jar` → classes transform, so one value covers both kinds of module.
     * `resolvedArtifacts` keeps the whole thing lazy — nothing resolves at
     * configuration time.
     */
    private fun ownedClasspath(project: Project, kspTaskName: String): Provider<String>? {
        val variant = kspTaskName.removePrefix("ksp").removeSuffix("Kotlin").replaceFirstChar(Char::lowercaseChar)
        val configuration = project.configurations.findByName(
            if (variant.isEmpty()) "compileClasspath" else variant + "CompileClasspath",
        ) ?: return null
        val local = project.rootProject.findProject(":kite:runtime") != null
        return configuration.incoming
            .artifactView { view ->
                view.attributes { it.attribute(ARTIFACT_TYPE, "android-classes-jar") }
                view.componentFilter { id ->
                    id is ProjectComponentIdentifier && !(local && id.projectPath.startsWith(":kite:"))
                }
                // A module that cannot produce classes must not fail the build over an
                // option that only widens what counts as project-owned.
                view.isLenient = true
            }
            .artifacts
            .resolvedArtifacts
            .map { artifacts -> artifacts.joinToString(File.pathSeparator) { it.file.absolutePath } }
    }

    /**
     * The decisions vocabulary — `@Root`/`@Bind`/`@Scoped`/`@Fresh`.
     *
     * `compileOnly` everywhere it is allowed: the annotations have SOURCE retention,
     * so with a compile-only dependency nothing of them can reach an APK by
     * construction rather than by convention.
     *
     * A base module that declares a dynamic feature is the exception, and not a small
     * one: AGP refuses *any* external `compileOnly` dependency there — the feature's
     * runtime classpath is derived from the base's, so a compile-only entry would be
     * missing at runtime, and AGP cannot know that these particular annotations are
     * erased before that matters. (Project dependencies are exempt, which is why this
     * repository's own demos never hit it — they resolve `:kite:rules` as a project.)
     * Declaring it `implementation` there ships a jar of annotation classes nothing
     * references, which R8 deletes from the release build; `scripts/check-apk-safety.sh`
     * is what keeps that honest.
     *
     * Read after evaluation, because `dynamicFeatures` is set in the module's own
     * `android { }` block. Read by reflection: it lives on `ApplicationExtension`,
     * which is generic in AGP 8 and not in AGP 9, so a typed call links against one
     * major and fails on the other. A version that answers nothing answers "no
     * dynamic features", which is today's behaviour.
     */
    private fun addRulesVocabulary(project: Project, local: Boolean) {
        val dependency: Any =
            if (local) project.dependencies.project(mapOf("path" to ":kite:rules"))
            else "$KITE_GROUP:rules:$KITE_VERSION"
        if (local) {
            project.dependencies.add("compileOnly", dependency)
            return
        }
        project.afterEvaluate {
            val configuration = if (hasDynamicFeatures(project)) "implementation" else "compileOnly"
            project.dependencies.add(configuration, dependency)
        }
    }

    /**
     * The androidx hosts generated code names — `Fragment` and `ComponentActivity` —
     * on every module's compile classpath, so a `:core` module that has no UI still
     * generates the `Fragment` form of its accessors and both ViewModel adapters.
     * Without this, a Fragment could not look up a binding from such a module.
     *
     * The first releases, non-transitive and compile-only: a version the module
     * already uses always wins resolution, nothing else comes along, and nothing
     * ships. Generated code only names the classes; an app that has no Fragment never
     * calls the Fragment forms. Skipped where AGP refuses external compile-only
     * dependencies — the base module of an app with dynamic features.
     */
    private fun addHostTypes(project: Project) {
        project.afterEvaluate {
            if (hasDynamicFeatures(project)) return@afterEvaluate
            for (coordinates in HOST_TYPES) {
                val dependency = project.dependencies.create(coordinates)
                (dependency as? org.gradle.api.artifacts.ExternalModuleDependency)?.isTransitive = false
                project.dependencies.add("compileOnly", dependency)
            }
        }
    }

    /** `android.dynamicFeatures` — non-empty only on the base module of a split app. */
    private fun hasDynamicFeatures(project: Project): Boolean {
        val android = project.extensions.findByName("android") ?: return false
        return runCatching {
            val features = android.javaClass.methods
                .firstOrNull { it.name == "getDynamicFeatures" && it.parameterCount == 0 }
                ?.invoke(android) as? Collection<*>
            features?.isNotEmpty() == true
        }.getOrDefault(false)
    }

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
        // Built further down, once it is known whether the board may start.
        val boardResolution = { project.files(project.configurations.detachedConfiguration(boardDependency)) }
        val hintPrefix = if (project.gradle.parent != null) ":" + project.rootProject.name else ""
        val kiteDir = project.layout.buildDirectory.dir("kite")
        // Only auto-start on a genuine build/install/run — not when kspDebugKotlin
        // is dragged in by `test`/`check`/`compile`. The link still prints everywhere.
        // The names the user typed live in the outermost build: inside an included
        // build of a composite, startParameter.taskNames is empty, which used to
        // silently disable the auto-start for every demo but the root one.
        var root = project.gradle
        while (true) root = root.parent ?: break
        val buildLike = root.startParameter.taskNames.any { requested ->
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
        // Empty when the board will not start. The task action captures it, and the
        // configuration cache resolves whatever an action captures — so the real
        // classpath must not even exist then, or every cached debug build downloads it.
        val boardClasspath = if (autostart) boardResolution() else project.files()
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
                    boardClasspath = boardClasspath.map { it.absolutePath },
                    // In a composite the user types the outer path, and task.path
                    // only knows the inner one.
                    taskPath = hintPrefix + task.path,
                )
            }
        }
    }

    /**
     * `kiteBoardStop` — the other half of `kiteBoard`. The board outlives the build
     * that started it on purpose, so something has to end it; killing a process by
     * hand is not an interface.
     */
    private fun registerBoardStopTask(project: Project): TaskProvider<*> {
        val port = project.providers.gradleProperty("kite.boardPort").orNull?.toIntOrNull()
            ?: DEFAULT_BOARD_PORT
        return project.tasks.register("kiteBoardStop") { task ->
            task.group = "kite"
            task.description = "Stops the dependency-board server."
            task.outputs.upToDateWhen { false }
            task.doLast {
                when (val stopped = BoardLink.stop(port)) {
                    is BoardLink.Stopped.Done ->
                        task.logger.lifecycle("\n  Dependency board: stopped (pid ${stopped.pid}, port $port)\n")
                    is BoardLink.Stopped.NotTheBoard -> task.logger.lifecycle(
                        "\n  Dependency board: pid ${stopped.pid} recorded for port $port is no longer the board — left it alone\n",
                    )
                    BoardLink.Stopped.Nothing ->
                        task.logger.lifecycle("\n  Dependency board: nothing serving port $port\n")
                }
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

        /** AGP's own artifact-type attribute — see [ownedClasspath]. */
        val ARTIFACT_TYPE: Attribute<String> = Attribute.of("artifactType", String::class.java)

        /** Generated registry class files — how a module with a graph is recognised. */
        const val REGISTRY_SUFFIX = "_BindingRegistry.class"
    }
}

/**
 * The oldest artifacts declaring the hosts generated code names, and the supertypes the
 * compiler has to see to type-check them (see addHostTypes) — nothing else.
 */
private val HOST_TYPES = listOf(
    "androidx.fragment:fragment:1.0.0",
    "androidx.activity:activity:1.0.0",
    "androidx.core:core:1.0.0", // androidx.core.app.ComponentActivity
    "androidx.lifecycle:lifecycle-common:2.0.0", // LifecycleOwner
    "androidx.lifecycle:lifecycle-viewmodel:2.0.0", // ViewModelStoreOwner
    "androidx.savedstate:savedstate:1.0.0", // SavedStateRegistryOwner
)

/**
 * The post-build board announcement: probe the port, print a clickable link, and
 * (by default, off under CI) start the board server detached so the link works.
 * Pure host-side dev sugar — it touches nothing that reaches an APK, and any
 * failure is swallowed so it can never break a build.
 */
internal object BoardLink {

    /** Entry point of the board process (`:kite:board`), spawned detached. */
    private const val BOARD_MAIN = "com.kite.di.board.BoardMain"

    /**
     * Keyed by port, not by build directory: whoever started the board, any module
     * has to be able to stop it — including one in a different Gradle build of the
     * same repository.
     */
    fun pidFile(port: Int): File =
        File(System.getProperty("java.io.tmpdir"), "kite-board-$port.pid")

    sealed interface Stopped {
        data class Done(val pid: Long) : Stopped
        /** The pid file outlived the board — a reboot, a crash — and the pid was reused. */
        data class NotTheBoard(val pid: Long) : Stopped
        data object Nothing : Stopped
    }

    /**
     * Stops the board on [port]. Only a process whose command line is the board's is
     * killed: a pid file left behind by a reboot or a crash can name any process the
     * system has since given that pid.
     */
    fun stop(port: Int): Stopped {
        val file = pidFile(port)
        val pid = runCatching { file.readText().trim().toLong() }.getOrNull()
        val handle = pid?.let { ProcessHandle.of(it).orElse(null) }
        file.delete()
        if (pid == null || handle == null || !handle.isAlive) return Stopped.Nothing
        if (!isBoard(handle)) return Stopped.NotTheBoard(pid)
        handle.destroy()
        return Stopped.Done(pid)
    }

    private fun isBoard(handle: ProcessHandle): Boolean {
        val info = handle.info()
        val commandLine = info.commandLine().orElse("") + " " + info.arguments().map { it.joinToString(" ") }.orElse("")
        return BOARD_MAIN in commandLine
    }

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
        /** This module's own task path, so every hint is copy-pasteable. */
        taskPath: String,
    ) {
        val url = "http://localhost:$port"
        if (isUp(port)) {
            val mine = appIdOf(graphFile)
            val serving = servedAppId(port)
            // One port, one board. With several apps in a repository the first build
            // to start it wins, and the others used to print a link to somebody
            // else's graph — the same picture, quietly about the wrong app.
            if (mine != null && serving != null && mine != serving) {
                logger.lifecycle("\n  Dependency board: $url  — serving $serving, not $mine")
                logger.lifecycle("  ↳ ./gradlew $taskPath" + "Stop   then build again,")
                logger.lifecycle("    or keep both: -Pkite.boardPort=<other port>\n")
            } else {
                logger.lifecycle("\n  Dependency board (live): $url\n")
            }
            return
        }

        if (autostart && start(boardClasspath, graphFile, fragmentFiles, decisionsFile, repoRoot, port, logFile)) {
            logger.lifecycle("\n  Dependency board: $url  (starting)")
            logger.lifecycle("  stop it with: ./gradlew $taskPath" + "Stop")
            logger.lifecycle("  server log → $logFile\n")
            return
        }

        logger.lifecycle("\n  Dependency board: $url")
        logger.lifecycle("  ↳ not serving yet — run:  ./gradlew $taskPath")
        logger.lifecycle("")
    }

    /** `appId` out of a graph snapshot — the board's own identity for that app. */
    private fun appIdOf(graphFile: File): String? = runCatching {
        APP_ID.find(graphFile.readText())?.groupValues?.get(1)
    }.getOrNull()

    /**
     * What the running board is serving, asked over the same port it serves on. Bounded:
     * whatever else may be listening there must not hang the build.
     */
    internal fun servedAppId(port: Int, timeoutMillis: Int = 1_000): String? = runCatching {
        val connection = java.net.URI("http://127.0.0.1:$port/api/meta").toURL().openConnection()
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        val text = connection.getInputStream().bufferedReader().use { it.readText() }
        APP_ID.find(text)?.groupValues?.get(1)
    }.getOrNull()

    private val APP_ID = Regex("\"appId\"\\s*:\\s*\"([^\"]*)\"")

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
                .also { runCatching { pidFile(port).writeText(it.pid().toString()) } }
            true
        }
    } catch (_: Exception) {
        false
    }

}
