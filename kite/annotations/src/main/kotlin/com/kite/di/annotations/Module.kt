package com.kite.di.annotations

/**
 * A container of [Provides] functions for types you don't own or that need
 * construction logic. Modules are auto-discovered — annotating is enough; there is
 * no component to register them with. Must be an `object` or have a no-arg
 * constructor (validated at compile time).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Module

/**
 * A function inside a [Module] whose return type becomes a binding. Parameters are
 * the binding's dependencies.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Provides

/**
 * Multibinding contribution: the annotated declaration becomes one element of
 * `Set<T>`. Inject `Set<T>` anywhere to receive all contributions — the classic
 * plugin pattern.
 *
 * On a [Provides] function, the return value is the element:
 *
 * ```kotlin
 * @Provides @IntoSet fun logging(): Interceptor = LoggingInterceptor()
 * @Provides @IntoSet fun auth(tokens: TokenStore): Interceptor = AuthInterceptor(tokens)
 *
 * @Injectable class Http @Inject constructor(interceptors: Set<Interceptor>)
 * ```
 *
 * On an [Injectable] class, an instance is the element; `bindTo` must name exactly
 * one type — the set's element type (no wrapper function needed):
 *
 * ```kotlin
 * @Injectable(bindTo = [Interceptor::class]) @IntoSet
 * class LoggingInterceptor(log: Logger) : Interceptor
 * ```
 *
 * Contributions must be unscoped (elements are created per set resolution); a
 * scope annotation on a contribution is a compile-time error.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class IntoSet

/**
 * Map multibinding contribution: the annotated declaration becomes the entry under
 * [key] in `Map<String, T>`. Inject `Map<String, T>` anywhere to receive all
 * entries — the strategy/registry pattern:
 *
 * ```kotlin
 * @Provides @IntoMap("json") fun json(): Parser = JsonParser()
 * @Provides @IntoMap("xml")  fun xml(): Parser = XmlParser()
 *
 * @Injectable class Decoder @Inject constructor(parsers: Map<String, Parser>)
 * ```
 *
 * Also allowed on an [Injectable] class, with `bindTo` naming exactly one type —
 * the map's value type (see [IntoSet]).
 *
 * Entry keys must be unique per value type (compile-time error otherwise). Like
 * [IntoSet], contributions must be unscoped — entries are created per map resolution.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class IntoMap(val key: String)
