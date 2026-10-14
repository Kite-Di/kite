package com.kite.di.processor.codegen

import com.kite.di.graph.Key
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.InferredBy
import com.kite.di.processor.model.Issue
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.Severity
import com.kite.di.processor.model.TypeRef
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

/**
 * Emits the typed lookups that replace `Kite.get<T>()` and `by injected()` (ADR 15):
 *
 * ```kotlin
 * private val presenter: FirstPresenter by firstPresenter()   // in a Fragment
 * private val analytics by analytics()                         // in any Activity, Service, View
 * ```
 *
 * A lookup written by hand names its type inside a function body, where the processor
 * cannot see it — so a missing binding or a wrong scope surfaced on the device. A
 * generated accessor exists only for a key that has a binding, and only on the
 * receivers that have the binding's scope ([receivers]); anything else does not
 * compile.
 *
 * Keys that get one are the keys a runtime lookup legitimately names: the interfaces
 * a binding is bound to (marked ones as `<mark><Type>()`), the types it supplies, and
 * `@Root` classes. A plain class reached only through constructors gets none — it is
 * not something to look up.
 */
object AccessorGenerator {

    private val CONTEXT = ClassName("android.content", "Context")
    private val ACTIVITY = ClassName("android.app", "Activity")
    private val FRAGMENT = ClassName("androidx.fragment.app", "Fragment")
    private val VIEW = ClassName("android.view", "View")
    private val LAZY = ClassName("kotlin", "Lazy")
    private val LOOKUP = MemberName("com.kite.di.runtime.android", "kiteLookup", isExtension = true)
    private val GENERATED_API = ClassName("com.kite.di.runtime", "KiteGeneratedApi")
    private val OPT_IN = ClassName("kotlin", "OptIn")

    /**
     * One accessor to emit: [key] of [type], callable on [receivers], declared in
     * [packageName] — the type's own package when the project owns the type; a
     * supplied type belongs to a library (or to `java.*`, where nothing may be
     * declared), so its accessor sits beside the supplier.
     */
    class Accessor(
        val key: Key,
        val type: TypeRef,
        val name: String,
        val receivers: List<ClassName>,
        val internal: Boolean,
        val packageName: String,
    )

    /**
     * Who may ask for a key whose resolution pins [level]: whatever has that scope.
     * A Context or View resolves in its host Activity's scope *if* there is one — a
     * Service has none — so only app-wide keys are offered there.
     */
    fun receivers(level: Int): List<ClassName> = when {
        level <= 0 -> listOf(CONTEXT, FRAGMENT, VIEW)
        level == 1 -> listOf(ACTIVITY, FRAGMENT)
        level == 2 -> listOf(FRAGMENT)
        else -> emptyList() // custom scopes: through their handle (ADR 15 §4)
    }

    /** The accessors this module owns, and the clashes it could not emit. */
    fun plan(scan: ScanResult, levelOf: (Key) -> Int): Pair<List<Accessor>, List<Issue>> {
        val accessors = mutableListOf<Accessor>()
        val issues = mutableListOf<Issue>()
        val taken = mutableMapOf<String, Accessor>() // "package.name" → first claimant
        for (b in scan.bindings.sortedBy { it.key.id }) {
            for ((key, type, packageName) in lookupKeys(b)) {
                val receivers = receivers(levelOf(key))
                if (receivers.isEmpty()) continue
                val mark = key.qualifier?.let { scan.marks[it] }
                val name = decapitalize((mark?.simpleNames?.joinToString("") ?: "") + type.simpleNames.joinToString(""))
                val accessor = Accessor(
                    key, type, name, receivers,
                    internal = type.fqn in scan.internalTypes || (mark != null && mark.fqn in scan.internalTypes),
                    packageName = packageName,
                )
                val slot = "$packageName.$name"
                val first = taken.putIfAbsent(slot, accessor)
                if (first != null) {
                    issues += Issue(
                        Severity.WARNING,
                        "No accessor for ${key.id}: $packageName.$name() already looks up ${first.key.id}. " +
                            "Rename one of the types, or look it up with Kite.get (@UncheckedLookup).",
                    )
                    continue
                }
                accessors += accessor
            }
        }
        return accessors to issues
    }

    /**
     * One file per type and module: two modules adding accessors to one package must
     * not collide. [available] says which receiver classes this module can name — a
     * module without androidx.fragment gets no `Fragment` accessors.
     */
    fun accessorFiles(
        accessors: List<Accessor>,
        moduleName: String,
        marks: Map<String, TypeRef>,
        available: (ClassName) -> Boolean = { true },
    ): List<FileSpec> =
        accessors.groupBy { it.packageName to it.type }.map { (where, forType) ->
            val (packageName, type) = where
            val fileName = type.simpleNames.joinToString("_") + "_" + sanitizeModuleName(moduleName) + "_Accessors"
            val file = FileSpec.builder(packageName, fileName)
                .addFileComment("Generated by the Kite processor for module %L — do not edit.", moduleName)
                .addAnnotation(
                    AnnotationSpec.builder(OPT_IN).addMember("%T::class", GENERATED_API).build(),
                )
            for (accessor in forType) {
                for (receiver in accessor.receivers.filter(available)) {
                    file.addFunction(accessorFun(accessor, receiver, marks))
                }
            }
            file.build()
        }

    private fun accessorFun(accessor: Accessor, receiver: ClassName, marks: Map<String, TypeRef>): FunSpec {
        val type = accessor.type.className()
        val fn = FunSpec.builder(accessor.name)
            .receiver(receiver)
            .returns(LAZY.parameterizedBy(type))
            .addKdoc("`by %L()` — %L from the graph, resolved on first access.", accessor.name, accessor.type.displayName)
            .addStatement(
                "return %M(%L)",
                LOOKUP,
                FactoryGenerator.keyLiteral(type, accessor.key.qualifier, marks),
            )
        if (accessor.internal) fn.addModifiers(KModifier.INTERNAL)
        return fn.build()
    }

    private fun lookupKeys(b: BindingModel): List<Triple<Key, TypeRef, String>> = buildList {
        for ((key, type) in b.extraKeys.zip(b.extraKeyTypes)) add(Triple(key, type, type.packageName))
        for ((key, type) in b.suppliedKeys.zip(b.suppliedKeyTypes)) add(Triple(key, type, b.targetType.packageName))
        if (b.inferredBy == InferredBy.ROOT_RULE) add(Triple(b.key, b.keyType, b.keyType.packageName))
    }

    private fun decapitalize(name: String): String = name.replaceFirstChar(Char::lowercaseChar)
}
