package com.kite.di.processor.scan

import com.kite.di.graph.ScopeDef
import com.kite.di.processor.ProcessorOptions
import com.kite.di.processor.model.Issue
import com.kite.di.processor.model.Severity
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType

/**
 * The module's decisions: `@Root` / `@Bind` / `@Scoped` annotations
 * on a holder object (conventionally `GraphRules.kt`), read from this
 * compilation's sources. SOURCE retention scopes discovery to the current
 * module — the same per-module boundary the graph.rules file had, now with
 * compile-checked class references instead of FQN strings.
 */
data class RuleSet(
    val roots: List<RootRule> = emptyList(),
    val binds: List<BindRule> = emptyList(),
    val scopes: List<ScopeRule> = emptyList(),
    val issues: List<Issue> = emptyList(),
    /** Repo-root-relative path of the (first) holder's file — the decision cards' write target. */
    val holderFile: String? = null,
    /** Simple name of the holder object inside [holderFile]. */
    val holderObject: String? = null,
) {
    fun isEmpty(): Boolean = roots.isEmpty() && binds.isEmpty() && scopes.isEmpty() && issues.isEmpty()

    /** `GraphRules.kt` when no holder exists yet — for hints that name the file. */
    val holderFileName: String get() = holderFile?.substringAfterLast('/') ?: "GraphRules.kt"

    companion object {
        val EMPTY = RuleSet()
    }
}

/** All rules carry `where` = `path:line` of the annotation, for error messages. */
data class RootRule(val fqn: String, val where: String)
data class BindRule(val interfaceFqn: String, val implFqn: String, val where: String)
data class ScopeRule(val fqn: String, val scope: ScopeDef?, val where: String)

/** FQNs of the `:kite:rules` vocabulary — compileOnly, so referenced by name. */
object RulesNames {
    const val PACKAGE = "com.kite.di.rules"
    const val ROOT = "$PACKAGE.Root"
    const val BIND = "$PACKAGE.Bind"
    const val SCOPED = "$PACKAGE.Scoped"

    /** Mirrors `com.kite.di.rules.UNSET_LEVEL` (the `Scoped.level` default). */
    const val UNSET_LEVEL = Int.MIN_VALUE
}

/** `@Scoped(..., scope, level)` → a [ScopeDef], `null` for "none", or an error message. */
object ScopeTargets {
    private val BUILT_IN = mapOf(
        "singleton" to ScopeDef("Singleton", 0),
        "activity" to ScopeDef("ActivityScoped", 1),
        "fragment" to ScopeDef("FragmentScoped", 2),
    )

    sealed interface Resolution {
        /** [def] is null for "none" — an explicit unscoped override. */
        data class Ok(val def: ScopeDef?) : Resolution
        data class Error(val message: String) : Resolution
    }

    fun resolve(scope: String, level: Int): Resolution {
        val builtIn = BUILT_IN[scope]
        return when {
            scope == "none" -> Resolution.Ok(null)
            builtIn != null -> Resolution.Ok(builtIn)
            scope.isBlank() -> Resolution.Error(
                "scope name is blank — use singleton | activity | fragment | none | a custom name with a level",
            )
            level == RulesNames.UNSET_LEVEL -> Resolution.Error(
                "custom scope \"$scope\" needs a level, e.g. @Scoped(..., \"$scope\", level = 10) — " +
                    "levels order nesting (Singleton=0, ActivityScoped=1, FragmentScoped=2)",
            )
            else -> Resolution.Ok(ScopeDef(scope, level))
        }
    }
}

class RulesScanner(private val resolver: Resolver, private val options: ProcessorOptions) {

    private val issues = mutableListOf<Issue>()

    fun scan(): RuleSet {
        val holders = sequenceOf(RulesNames.ROOT, RulesNames.BIND, RulesNames.SCOPED)
            .flatMap { resolver.getSymbolsWithAnnotation(it) }
            .filterIsInstance<KSClassDeclaration>()
            .distinct()
            .sortedWith(compareBy({ where(it) }, { it.simpleName.asString() }))
            .toList()

        val roots = mutableListOf<RootRule>()
        val binds = mutableListOf<BindRule>()
        val scopes = mutableListOf<ScopeRule>()

        for (holder in holders) {
            for (annotation in holder.annotations) {
                when (annotationFqn(annotation)) {
                    RulesNames.ROOT -> readRoot(annotation)?.let { roots += it }
                    RulesNames.BIND -> readBind(annotation)?.let { binds += it }
                    RulesNames.SCOPED -> readScoped(annotation)?.let { scopes += it }
                }
            }
        }

        val first = holders.firstOrNull()
        return RuleSet(
            roots = roots,
            binds = binds,
            scopes = scopes,
            issues = issues,
            holderFile = first?.let { holder ->
                (holder.location as? FileLocation)?.filePath
                    ?.removePrefix(options.rootDir)?.trimStart('/', '\\')
            },
            holderObject = first?.simpleName?.asString(),
        )
    }

    private fun readRoot(annotation: KSAnnotation): RootRule? {
        val type = classArg(annotation, "type", 0) ?: return null
        return RootRule(type, where(annotation))
    }

    private fun readBind(annotation: KSAnnotation): BindRule? {
        val type = classArg(annotation, "type", 0) ?: return null
        val to = classArg(annotation, "to", 1) ?: return null
        return BindRule(type, to, where(annotation))
    }

    private fun readScoped(annotation: KSAnnotation): ScopeRule? {
        val type = classArg(annotation, "type", 0) ?: return null
        val scope = arg(annotation, "scope", 1) as? String ?: run {
            error(annotation, "@Scoped($type::class): could not read the scope name.")
            return null
        }
        val level = arg(annotation, "level", 2) as? Int ?: RulesNames.UNSET_LEVEL
        return when (val resolution = ScopeTargets.resolve(scope, level)) {
            is ScopeTargets.Resolution.Ok -> ScopeRule(type, resolution.def, where(annotation))
            is ScopeTargets.Resolution.Error -> {
                error(annotation, "@Scoped(${type.substringAfterLast('.')}::class, \"$scope\"): ${resolution.message}.")
                null
            }
        }
    }

    // ---- plumbing -----------------------------------------------------------------

    private fun annotationFqn(annotation: KSAnnotation): String? {
        // Cheap short-name gate before resolving the annotation type.
        if (annotation.shortName.asString() !in setOf("Root", "Bind", "Scoped")) return null
        return annotation.annotationType.resolve().declaration.qualifiedName?.asString()
    }

    private fun arg(annotation: KSAnnotation, name: String, index: Int): Any? =
        (annotation.arguments.firstOrNull { it.name?.asString() == name }
            ?: annotation.arguments.getOrNull(index))?.value

    /** A `KClass` argument → FQN string, or null (+ error) when it doesn't resolve. */
    private fun classArg(annotation: KSAnnotation, name: String, index: Int): String? {
        val type = arg(annotation, name, index) as? KSType
        val fqn = type?.takeUnless { it.isError }?.declaration?.qualifiedName?.asString()
        if (fqn == null) {
            error(
                annotation,
                "@${annotation.shortName.asString()}: could not resolve the '$name' class reference.",
            )
        }
        return fqn
    }

    private fun error(node: KSNode, message: String) {
        issues += Issue(Severity.ERROR, "${where(node)} — $message")
    }

    private fun where(node: KSNode): String {
        val location = node.location as? FileLocation ?: return "<unknown>"
        val path = location.filePath.removePrefix(options.rootDir).trimStart('/', '\\')
        return "$path:${location.lineNumber}"
    }
}
