package com.kite.di.processor.validate

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.kite.di.processor.model.ClasspathIndex
import com.kite.di.processor.model.Issue
import com.kite.di.processor.model.Severity

/**
 * The aggregate pass only: checks that every binding this application merges can
 * actually be constructed here.
 *
 * Each module validates its own closure, and a consumer is normally content to
 * know that a key exists somewhere on the classpath. That holds while every
 * module of the runtime is visible to the application's compilation — and Gradle
 * breaks it quietly: `implementation` is not transitive, so a module pulled in
 * through another module's `implementation` never reaches the app's compile
 * classpath. Its registry is missing from `MergedRegistry`, and the first
 * resolution that needs it fails on the device.
 *
 * So the app re-reads the constructors of the bindings it is about to merge. A
 * parameter whose type will not resolve here is exactly that case: the module
 * that owns the type is invisible to this compilation.
 *
 * Deliberately narrow. Only unresolvable types are reported — a type that
 * resolves but has no binding is already the owning module's business, and
 * judging it here would need the scanner's full key logic (deferred wrappers,
 * sets, graph arguments, built-ins) and would misfire without it.
 */
object ClosureValidator {

    fun validate(resolver: Resolver, classpath: ClasspathIndex): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((fqn, binding) in classpath.provided) {
            val declaration = resolver.getClassDeclarationByName(resolver.getKSNameFromString(fqn))
                ?: continue
            if (declaration.classKind != ClassKind.CLASS || declaration.isAbstract()) continue
            val constructor = declaration.primaryConstructor ?: continue

            for (parameter in constructor.parameters) {
                if (parameter.hasDefault) continue // optional: the default stands in
                if (!parameter.type.resolve().isError) continue

                val name = parameter.name?.asString() ?: "?"
                issues += Issue(
                    Severity.ERROR,
                    buildString {
                        appendLine("Unreachable dependency: ${declaration.simpleName.asString()}, constructor parameter '$name'")
                        appendLine("  provided by: ${binding.module}, merged into this application")
                        appendLine("  its type is not on this module's compile classpath, so the module that")
                        appendLine("  provides it is missing from the merged graph and resolution would fail at runtime.")
                        append(
                            "  hint: ${binding.module} resolves it through an `implementation` dependency, which is " +
                                "not transitive — depend on that module here too, or have ${binding.module} export " +
                                "it with api(...). Open ${declaration.simpleName.asString()} to see the type.",
                        )
                    },
                )
            }
        }
        return issues
    }
}
