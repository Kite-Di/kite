package com.kite.di.processor.codegen

import com.kite.di.processor.model.GraphArg
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec

/**
 * Emits the application module's startup façade:
 *
 * ```kotlin
 * Graph.start(this, apiKey = BuildConfig.API_KEY, timeoutMillis = 5_000)
 * ```
 *
 * Parameters are the union of every module's graph arguments (leaf constructor
 * parameters nothing provides �), sorted by name. Each becomes an
 * instance binding under `Key(type, qualifier = name)`, which is exactly what the
 * generated factories resolve.
 */
object GraphGenerator {

    private val APPLICATION = ClassName("android.app", "Application")
    private val INJECTOR = ClassName("com.kite.di.runtime", "Kite")
    private val CONFIG = ClassName("com.kite.di.runtime", "KiteConfig")
    private val INSTANCE_FACTORY = ClassName("com.kite.di.runtime", "InstanceFactory")

    fun graphFile(args: List<GraphArg>, includeProvenance: Boolean): FileSpec {
        val sorted = args.sortedBy { it.name }
        val start = FunSpec.builder("start")
            .addKdoc(
                "Starts Kite. Every parameter (besides the application and config) is a " +
                    "graph argument: a leaf constructor parameter somewhere in the graph that nothing " +
                    "provides — supplied once here, visible in the signature, checked by the compiler.",
            )
            .addParameter("app", APPLICATION)
        for (arg in sorted) {
            start.addParameter(arg.name, arg.type.className())
        }
        start.addParameter(
            ParameterSpec.builder("config", CONFIG).defaultValue("%T()", CONFIG).build()
        )

        val records = CodeBlock.builder()
        for (arg in sorted) {
            records.add(
                "%T(\nkey = %L,\nfactory = %T(%L),\nscopeLevel = 0,\nscopeName = %S,\n",
                RuntimeNames.BINDING_RECORD,
                FactoryGenerator.keyLiteral(arg.type.className(), qualifier = arg.name),
                INSTANCE_FACTORY,
                arg.name,
                "Singleton",
            )
            if (includeProvenance) {
                records.add("declaration = %S,\n", "Graph.start(${arg.name})")
            }
            records.add("),\n")
        }

        start.addCode(
            CodeBlock.builder()
                .add("%T.init(\n", INJECTOR)
                .indent()
                .add("app = app,\nconfig = config,\narguments = listOf(\n")
                .indent()
                .add(records.build())
                .unindent()
                .add("),\n")
                .unindent()
                .add(")\n")
                .build()
        )

        val type = TypeSpec.objectBuilder("Graph")
            .addKdoc("Generated startup façade — do not edit. Call from Application.onCreate().")
            .addFunction(start.build())
        return FileSpec.builder(RuntimeNames.GENERATED_PACKAGE, "Graph").addType(type.build()).build()
    }
}
