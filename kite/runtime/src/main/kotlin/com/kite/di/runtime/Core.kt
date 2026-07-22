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
    val qualifier: String? = null,
    /** Set multibinding: the element class; [type] is then `Set::class.java`. */
    element: Class<*>? = null,
    /** Map multibinding: the value class; [type] is then `Map::class.java` (string keys). */
    mapValue: Class<*>? = null,
) {
    /** Boxed canonical form: codegen emits `Int::class.java` (primitive `int`), while
     *  generic call sites box — canonicalizing makes both construct equal keys. */
    val type: Class<*> = box(type)
    val element: Class<*>? = element?.let(::box)
    val mapValue: Class<*>? = mapValue?.let(::box)

    /**
     * Dev-facing id matching compile-time graph node ids (Kotlin FQNs, `qualifier@type`).
     * Derived from [Class.getName] at call time: correct in unminified builds (where the
     * inspector runs); under R8 renaming it yields obfuscated names — by design, since
     * neither the id strings nor the inspector ship to users.
     */
    val id: String
        get() {
            val typeId = element?.let { "kotlin.collections.Set<${it.kotlinFqn()}>" }
                ?: mapValue?.let { "kotlin.collections.Map<kotlin.String,${it.kotlinFqn()}>" }
                ?: type.kotlinFqn()
            return if (qualifier == null) typeId else "$qualifier@$typeId"
        }

    override fun equals(other: Any?): Boolean = other is Key &&
        type == other.type && qualifier == other.qualifier &&
        element == other.element && mapValue == other.mapValue

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (qualifier?.hashCode() ?: 0)
        result = 31 * result + (element?.hashCode() ?: 0)
        result = 31 * result + (mapValue?.hashCode() ?: 0)
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

/** Implemented by KSP-generated `<Type>_MemberInjector` classes for @Inject fields. */
interface MemberInjector<T : Any> {
    fun inject(target: T, resolver: Resolver, scope: ScopeNode)
}

/** What generated factories resolve their dependencies through. */
interface Resolver {
    fun <T : Any> resolve(key: Key, scope: ScopeNode): T
    fun <T : Any> provider(key: Key, scope: ScopeNode): Provider<T>
    fun <T : Any> deferred(key: Key, scope: ScopeNode): Lazy<T>
}

/**
 * Aggregates @IntoSet contributions into one `Set<T>` binding. Elements are
 * created per set resolution (contributions are unscoped by rule); iteration
 * order is contribution declaration order.
 */
class SetFactory(private val elementFactories: List<Factory<*>>) : Factory<Set<Any>> {
    override fun create(resolver: Resolver, scope: ScopeNode): Set<Any> =
        elementFactories.mapTo(LinkedHashSet()) { it.create(resolver, scope) }
}

/**
 * Aggregates @IntoMap contributions into one `Map<String, T>` binding. Entries are
 * created per map resolution (contributions are unscoped by rule); iteration order
 * is contribution declaration order. Entry-key uniqueness is a compile-time check.
 */
class MapFactory(private val entryFactories: Map<String, Factory<*>>) : Factory<Map<String, Any>> {
    override fun create(resolver: Resolver, scope: ScopeNode): Map<String, Any> =
        entryFactories.entries.associateTo(LinkedHashMap()) { (key, factory) ->
            key to factory.create(resolver, scope)
        }
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

    /** Member-injection target class → its generated injector. Class-keyed (not FQN
     *  strings) so the map survives R8 renaming and leaks no source names. */
    fun memberInjectors(): Map<Class<*>, MemberInjector<*>> = emptyMap()
}

class KiteException(message: String) : RuntimeException(message)
