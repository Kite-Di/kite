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
 */
object MergeValidator {

    fun validate(classpath: ClasspathIndex, selfModule: String): List<Issue> {
        val missing = linkedMapOf<String, MutableList<String>>()
        for ((module, required) in classpath.requires) {
            for (dependency in required) {
                if (dependency == selfModule || dependency in classpath.modules) continue
                missing.getOrPut(dependency) { mutableListOf() } += module
            }
        }
        return missing.map { (dependency, requiredBy) ->
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
