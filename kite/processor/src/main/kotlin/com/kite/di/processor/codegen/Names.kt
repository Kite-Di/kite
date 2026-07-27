package com.kite.di.processor.codegen

import com.kite.di.processor.model.TypeRef
import com.squareup.kotlinpoet.ClassName

internal object RuntimeNames {
    private const val RUNTIME = "com.kite.di.runtime"
    private const val GRAPH = "com.kite.di.graph"

    val FACTORY = ClassName(RUNTIME, "Factory")
    val RESOLVER = ClassName(RUNTIME, "Resolver")
    val SCOPE_NODE = ClassName(RUNTIME, "ScopeNode")
    val BINDING_RECORD = ClassName(RUNTIME, "BindingRecord")
    val SET_FACTORY = ClassName(RUNTIME, "SetFactory")
    val BINDING_REGISTRY = ClassName(RUNTIME, "BindingRegistry")
    val GRAPH_ARGS = ClassName(RUNTIME, "GraphArgs")
    /** The runtime (Class-reference) key — generated code must never embed FQN strings. */
    val KEY = ClassName(RUNTIME, "Key")
    val PROVENANCE = ClassName(GRAPH, "Provenance")

    const val GENERATED_PACKAGE = "com.kite.di.generated"
    const val PROVIDER_FQN = "$RUNTIME.Provider"
    const val LAZY_FQN = "$RUNTIME.Lazy"
}

internal fun TypeRef.className(): ClassName = ClassName(packageName, simpleNames)

/** ":feature:login-ui" → "FeatureLoginUi" — used for `<Module>_BindingRegistry`. */
fun sanitizeModuleName(gradlePath: String): String =
    gradlePath.split(':', '-', '_', '.').filter { it.isNotBlank() }
        .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        .ifEmpty { "Main" }
