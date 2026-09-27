package com.kite.di.runtime

/**
 * A class that puts a type it does not own into the graph.
 *
 * Inference reads the code, and implementing an interface *is* a binding — this is
 * that same rule applied to one more shape. `Http` below binds `OkHttpClient` in
 * addition to its own class key, so any module may take an `OkHttpClient`
 * constructor parameter while okhttp stays a dependency of `:core:network` alone:
 *
 * ```kotlin
 * class Http(
 *     private val cache: RequestCache,            // project class, resolved
 *     private val timeoutMillis: Long = 30_000,   // default → not a graph argument
 * ) : Supplies<OkHttpClient> {
 *     override val value: OkHttpClient = OkHttpClient.Builder()
 *         .callTimeout(timeoutMillis, MILLISECONDS)
 *         .cache(cache.asOkHttpCache())
 *         .build()
 * }
 * ```
 *
 * The supplier is an ordinary binding in every other respect: its own constructor
 * is inferred, its own unprovidable leaves still bubble up to `Graph.start`, and a
 * lifetime decision on it (`@Scoped`, `@Fresh`) carries to the type it supplies.
 * Singleton is the default, so one shared instance — one connection pool — needs no
 * annotation.
 *
 * [value] is read once per supplier instance, when the supplied type is first
 * resolved. Under the default lifetime that is once per process; `override val
 * value get() = …` therefore does not hand out a new object per injection — make the
 * *supplier* `@Fresh` for that.
 *
 * A type nothing supplies is unchanged: it remains a graph argument, supplied once
 * in `Graph.start`. This is additive.
 */
interface Supplies<T : Any> {
    val value: T
}

/**
 * Resolves the supplier and hands back what it supplies.
 *
 * Deliberately not `BindingRecord.extraKeys`: an extra key means one instance
 * answers a second key, while here the answer is a different object, so the
 * indirection has to exist. Shaped like [SetFactory], which also resolves keys other
 * than its own.
 */
class SuppliedFactory<T : Any>(private val supplier: Key) : Factory<T> {
    override fun create(resolver: Resolver, scope: ScopeNode): T {
        @Suppress("UNCHECKED_CAST")
        val supplies = resolver.resolve<Any>(supplier, scope) as Supplies<T>
        return supplies.value
    }
}
