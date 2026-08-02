package com.kite.di.runtime

import com.kite.di.graph.Provenance

/**
 * Runtime binding identity. Holds [Class] references — never FQN string literals —
 * so R8 renames the identity together with the classes themselves: no original
 * class name survives as a string constant in a shipped binary, and lookups keep
 * working under obfuscation. The human-readable FQN identity
 * ([com.kite.di.graph.Key]) exists only in compile-time artifacts
 * (graph.json, the board) that never leave the developer's machine.
 */
class Key(
    type: Class<*>,
    /** Graph arguments use the leaf parameter's name as the qualifier. */
    val qualifier: String? = null,
    /** Set multibinding: the element class; [type] is then `Set::class.java`. */
    element: Class<*>? = null,
) {
    /** Boxed canonical form: codegen emits `Int::class.java` (primitive `int`), while
     *  generic call sites box — canonicalizing makes both construct equal keys. */
    val type: Class<*> = box(type)
    val element: Class<*>? = element?.let(::box)

    /**
     * Dev-facing id matching compile-time graph node ids (Kotlin FQNs, `qualifier@type`).
     * Derived from [Class.getName] at call time: correct in unminified builds (where the
     * inspector runs); under R8 renaming it yields obfuscated names — by design, since
     * neither the id strings nor the inspector ship to users.
     */
    val id: String
        get() {
            val typeId = element?.let { "kotlin.collections.Set<${it.kotlinFqn()}>" }
                ?: type.kotlinFqn()
            return if (qualifier == null) typeId else "$qualifier@$typeId"
        }

    override fun equals(other: Any?): Boolean = other is Key &&
        type == other.type && qualifier == other.qualifier && element == other.element

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (qualifier?.hashCode() ?: 0)
        result = 31 * result + (element?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = id

    private companion object {
        val BOXED: Map<Class<*>, Class<*>> = listOf(
            Int::class, Long::class, Boolean::class, Double::class,
            Float::class, Short::class, Byte::class, Char::class,
        ).associate { it.javaPrimitiveType!! to it.javaObjectType }

        fun box(c: Class<*>): Class<*> = BOXED[c] ?: c

        /** Java binary names → the Kotlin FQNs the compile-time graph uses as node ids. */
        val KOTLIN_FQNS: Map<String, String> = mapOf(
            "java.lang.String" to "kotlin.String",
            "java.lang.Integer" to "kotlin.Int",
            "java.lang.Long" to "kotlin.Long",
            "java.lang.Boolean" to "kotlin.Boolean",
            "java.lang.Double" to "kotlin.Double",
            "java.lang.Float" to "kotlin.Float",
            "java.lang.Short" to "kotlin.Short",
            "java.lang.Byte" to "kotlin.Byte",
            "java.lang.Character" to "kotlin.Char",
            "java.lang.Object" to "kotlin.Any",
            "java.util.List" to "kotlin.collections.List",
            "java.util.Map" to "kotlin.collections.Map",
            "java.util.Set" to "kotlin.collections.Set",
        )

        fun Class<*>.kotlinFqn(): String = KOTLIN_FQNS[name] ?: name.replace('$', '.')
    }
}

/**
 * A new lookup per [get] call — for unscoped bindings that means a new instance.
 *
 * Also a plain `() -> T`: an injection site may declare a function type
 * (`cursors: () -> Cursor`) instead of `Provider<Cursor>` — same semantics, no
 * framework import at the site.
 */
interface Provider<T : Any> : () -> T {
    fun get(): T
    override fun invoke(): T = get()
}

/**
 * Memoized, thread-safe single instance, created on first [get].
 *
 * Also a [kotlin.Lazy]: an injection site may declare the standard-library
 * `Lazy<T>` (`.value`, `by` delegation) instead of this type — same semantics, no
 * framework import at the site.
 */
class Lazy<T : Any> internal constructor(provider: Provider<T>) : kotlin.Lazy<T> {
    private val delegate = kotlin.lazy { provider.get() }
    override val value: T get() = delegate.value
    override fun isInitialized(): Boolean = delegate.isInitialized()
    fun get(): T = delegate.value
}

/** Implemented by KSP-generated `<Type>_Factory` classes — plain constructor calls, no reflection. */
interface Factory<T : Any> {
    fun create(resolver: Resolver, scope: ScopeNode): T
}

/**
 * Wraps a ready instance as a factory — how graph arguments (`Graph.start`
 * parameters) enter the container.
 */
class InstanceFactory<T : Any>(private val instance: T) : Factory<T> {
    override fun create(resolver: Resolver, scope: ScopeNode): T = instance
}

/** What generated factories resolve their dependencies through. */
interface Resolver {
    fun <T : Any> resolve(key: Key, scope: ScopeNode): T
    fun <T : Any> provider(key: Key, scope: ScopeNode): Provider<T>
    fun <T : Any> deferred(key: Key, scope: ScopeNode): Lazy<T>
}

/**
 * Aggregates every implementation of an interface into one `Set<T>` binding
 * (inferred multibinding). Elements resolve through their own keys, so each
 * element respects its own scope — a singleton implementation is the shared
 * instance, an unscoped one is fresh per set resolution. Iteration order is
 * key order (deterministic).
 */
class SetFactory(private val elementKeys: List<Key>) : Factory<Set<Any>> {
    override fun create(resolver: Resolver, scope: ScopeNode): Set<Any> =
        elementKeys.mapTo(LinkedHashSet()) { resolver.resolve(it, scope) }
}

/** One binding as loaded from a generated registry. Immutable after [Kite.init]. */
class BindingRecord(
    val key: Key,
    val factory: Factory<*>,
    /** Extra keys from `@Injectable(bindTo = [...])` resolving to this same record. */
    val extraKeys: List<Key> = emptyList(),
    /** null = unscoped (new instance per injection). */
    val scopeLevel: Int? = null,
    val scopeName: String? = null,
    /** Class name, or `Module.function` for @Provides bindings — for error messages.
     *  Null in `stripProvenance` (release) builds: no source names ship as strings. */
    val declaration: String? = null,
    val provenance: Provenance? = null,
)

/** One per Gradle module, KSP-generated; merged by the generated `MergedRegistry`. */
interface BindingRegistry {
    fun bindings(): List<BindingRecord>
}

/**
 * Stamped on each generated registry: the module's graph arguments (leaf
 * constructor parameters that bubble up to `Graph.start`). The aggregate KSP run
 * reads these off the compile classpath to compose the full `Graph.start`
 * signature across modules. `KClass` references, not name strings.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class GraphArgs(
    val names: Array<String>,
    val types: Array<kotlin.reflect.KClass<*>>,
)

/**
 * Stamped on each generated registry: every key this module's registry provides —
 * binding classes plus the interfaces they are bound to. Downstream modules' KSP
 * runs read this off the compile classpath, so a constructor parameter of a type
 * another module provides resolves as a cross-module edge instead of bubbling up
 * to `Graph.start`. `KClass` references and logical scope names only — no class
 * name ever ships as a string ([scopeLevels] uses `Int.MIN_VALUE` for unscoped,
 * [scopeNames] an empty string). [ambiguous] lists interfaces with multiple
 * implementations and no `@Bind` decision in the owning module, so a downstream
 * consumer gets "add @Bind to [module]'s GraphRules.kt" instead of silence.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ProvidedKeys(
    /** Gradle path of the providing module (a logical name, like scope names). */
    val module: String,
    val types: Array<kotlin.reflect.KClass<*>>,
    val scopeNames: Array<String>,
    val scopeLevels: IntArray,
    val ambiguous: Array<kotlin.reflect.KClass<*>> = [],
)

class KiteException(message: String) : RuntimeException(message)
