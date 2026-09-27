package com.kite.di.processor.scan

import java.io.File
import java.util.zip.ZipFile

/**
 * Which types belong to the project and which are somebody's library.
 *
 * The question matters in exactly one place — [InferenceScanner.bindableSupertypes],
 * where a project interface is a binding and `okhttp3.Interceptor` is not — and it
 * cannot be answered from a name. `com.google.samples.*` is an application,
 * `com.google.android.material.*` is a library, and no prefix list tells them apart:
 * before this class, every app under `com.google.*` had its own interfaces
 * classified as third-party and got no interface bindings at all.
 *
 * The build knows the answer exactly. Gradle labels each compile-classpath entry
 * with a component identifier, and the ones belonging to this build are
 * `ProjectComponentIdentifier`s; the Kite Gradle plugin resolves that filter and
 * passes the resulting paths as `kite.projectClasspath`. Reading the package
 * directories out of them yields the set below — the packages this build produces,
 * whatever they are called.
 *
 * Kite's own modules are excluded by the plugin: inside this repository they *are*
 * project dependencies, and treating `com.kite.di.runtime` as project-owned would
 * make a class implementing `Provider` bind to it.
 */
internal class OwnedPackages private constructor(
    private val packages: Set<String>?,
) {

    /**
     * Is a declaration in [packageName] produced by one of this build's *other*
     * modules? This compilation's own packages are answered by the caller, which can
     * read them off the resolver.
     */
    fun owns(packageName: String): Boolean =
        packages?.contains(packageName) ?: FOREIGN_PREFIXES.none { packageName.startsWith(it) }

    internal companion object {

        /**
         * The pre-`kite.projectClasspath` heuristic, kept for a compilation KSP was
         * wired up by hand: it is wrong for whole families of package names, but
         * silently classifying *everything* as foreign would be worse — every
         * interface binding in the project would turn into a graph argument.
         */
        private val FOREIGN_PREFIXES = listOf(
            "kotlin.", "kotlinx.", "java.", "javax.",
            "android.", "androidx.", "com.google.", "org.jetbrains.", "org.intellij.",
        )

        /** No `kite.projectClasspath` — fall back to matching names. */
        val LEGACY = OwnedPackages(packages = null)

        /**
         * [entries] are the jars and class directories of this build's own modules.
         * Anything unreadable is skipped rather than failing the build: a missing
         * entry can only narrow the set, and the compilation's own sources — the
         * common single-module case — do not depend on it.
         */
        fun of(entries: List<File>): OwnedPackages {
            if (entries.isEmpty()) return OwnedPackages(emptySet())
            val packages = mutableSetOf<String>()
            for (entry in entries) runCatching {
                when {
                    entry.isDirectory -> collectFromDirectory(entry, packages)
                    entry.isFile -> collectFromJar(entry, packages)
                }
            }
            return OwnedPackages(packages)
        }

        private fun collectFromDirectory(root: File, into: MutableSet<String>) {
            val prefix = root.path.length + 1
            root.walkTopDown().forEach { file ->
                if (!file.isFile || file.extension != "class") return@forEach
                val relative = file.parentFile?.path?.drop(prefix).orEmpty()
                into += relative.replace(File.separatorChar, '.')
            }
        }

        private fun collectFromJar(jar: File, into: MutableSet<String>) {
            ZipFile(jar).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (entry.isDirectory || !name.endsWith(".class")) continue
                    into += name.substringBeforeLast('/', missingDelimiterValue = "").replace('/', '.')
                }
            }
        }
    }
}
