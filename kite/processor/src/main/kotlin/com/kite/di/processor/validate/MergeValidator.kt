package com.kite.di.processor.validate

import com.kite.di.processor.model.ClasspathIndex
import com.kite.di.processor.model.Issue
import com.kite.di.processor.model.Severity

/**
 * The aggregate pass only: every module the merged registries depend on has to be
 * merged too.
 *
 * `implementation` is not transitive, so a module reached only through another
 * module's `implementation` never lands on the application's compile classpath. Its
 * registry is then absent from `MergedRegistry`, the build passes, and the first
 * resolution that needs it dies on the device with a `No binding for …` that names
 * the type and cannot name the cause.
 *
 * [ClosureValidator] catches the case where the missing type is a constructor
 * parameter of a binding the application merges — it re-reads those constructors. It
 * cannot catch a demand that belongs to no binding: a module whose only graph content
 * is a ViewModel exports no keys at all, while its generated
 * `remember<Name>ViewModel()` adapter resolves whatever that ViewModel's constructor
 * asks for. So each module also records *which modules it resolved keys from*
 * (`@ProvidedKeys.requires`), and this is where that list is checked.
 *
 * Module paths, not types: the type that would be named is exactly the one missing
 * from the classpath being checked, so a class reference could not be read back.
 *
 * A recorded demand is not the only way in. `Kite.get<T>()` and `by injected()` sit
 * inside function bodies, which KSP does not read, so a module can be needed without
 * any metadata saying so. The build sees the other half: [unmergedModules] lists the
 * modules of this build whose graph is in the application at runtime and not in its
 * merge, whatever the reason. Both are errors — a graph module that ships is part of
 * the graph, and "nothing happens to resolve from it today" is not a guarantee anyone
 * can hold.
 */
object MergeValidator {

    fun validate(
        classpath: ClasspathIndex,
        selfModule: String,
        /** From the plugin: modules on the runtime classpath, off the compile classpath. */
        unmergedModules: List<String> = emptyList(),
    ): List<Issue> {
        val missing = linkedMapOf<String, MutableList<String>>()
        for ((module, required) in classpath.requires) {
            for (dependency in required) {
                if (dependency == selfModule || dependency in classpath.modules) continue
                missing.getOrPut(dependency) { mutableListOf() } += module
            }
        }
        // Present in the app, unmerged, and nothing recorded a demand for it — a runtime
        // lookup is the usual reason, and there is no metadata that could have shown it.
        val silent = unmergedModules.filter { it != selfModule && it !in missing && it !in classpath.modules }
        return silent.map { dependency ->
            Issue(
                Severity.ERROR,
                buildString {
                    appendLine("Module $dependency has a graph this application does not merge")
                    appendLine("  its classes ship here — it is on the runtime classpath — while its registry is")
                    appendLine("  not on the compile classpath, so its bindings are registered nowhere")
                    append(
                        "  hint: `implementation` is not transitive — depend on $dependency from this module, " +
                            "or have the module that pulls it in export it with api(project(\"$dependency\")). " +
                            "Nothing here has to resolve from it yet: a graph that ships is part of this graph.",
                    )
                },
            )
        } + missing.map { (dependency, requiredBy) ->
            Issue(
                Severity.ERROR,
                buildString {
                    appendLine("Module $dependency is missing from the merged graph")
                    for (module in requiredBy.sorted()) {
                        appendLine("  $module resolves keys from it, and $module is merged into this application")
                    }
                    appendLine(
                        "  $dependency is not on this application's compile classpath, so nothing registers " +
                            "its bindings and resolution would fail at runtime",
                    )
                    append(
                        "  hint: `implementation` is not transitive — export it with " +
                            "api(project(\"$dependency\")) from ${requiredBy.sorted().first()}, " +
                            "or depend on $dependency from this module too.",
                    )
                },
            )
        }
    }
}
