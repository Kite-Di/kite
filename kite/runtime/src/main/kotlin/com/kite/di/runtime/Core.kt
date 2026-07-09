package com.kite.di.runtime

import com.kite.di.graph.Key
import com.kite.di.graph.Provenance

/** A new lookup per [get] call — for unscoped bindings that means a new instance. */
interface Provider<T : Any> {
    fun get(): T
}

/** Memoized, thread-safe single instance, created on first [get]. */
class Lazy<T : Any> internal constructor(provider: Provider<T>) {
    private val delegate = kotlin.lazy { provider.get() }
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

/** One binding as loaded from a generated registry. Immutable after [Kite.init]. */
class BindingRecord(
    val key: Key,
    val factory: Factory<*>,
    /** Extra keys from `@Injectable(bindTo = [...])` resolving to this same record. */
    val extraKeys: List<Key> = emptyList(),
    /** null = unscoped (new instance per injection). */
    val scopeLevel: Int? = null,
    val scopeName: String? = null,
    /** Class name, or `Module.function` for @Provides bindings — for error messages. */
    val declaration: String? = null,
    val provenance: Provenance? = null,
)

/** One per Gradle module, KSP-generated; merged by the generated `MergedRegistry`. */
interface BindingRegistry {
    fun bindings(): List<BindingRecord>

    /** FQN of the member-injection target class → its generated injector. */
    fun memberInjectors(): Map<String, MemberInjector<*>> = emptyMap()
}

class KiteException(message: String) : RuntimeException(message)
