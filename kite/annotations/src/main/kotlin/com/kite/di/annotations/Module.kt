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
 * Multibinding contribution: the [Provides] function's return value becomes one
 * element of `Set<T>`. Inject `Set<T>` anywhere to receive all contributions —
 * the classic plugin pattern:
 *
 * ```kotlin
 * @Provides @IntoSet fun logging(): Interceptor = LoggingInterceptor()
 * @Provides @IntoSet fun auth(tokens: TokenStore): Interceptor = AuthInterceptor(tokens)
 *
 * @Injectable class Http @Inject constructor(interceptors: Set<Interceptor>)
 * ```
 *
 * Contributions must be unscoped (elements are created per set resolution); a
 * scope annotation on an @IntoSet function is a compile-time error.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class IntoSet

/**
 * Map multibinding contribution: the [Provides] function's return value becomes the
 * entry under [key] in `Map<String, T>`. Inject `Map<String, T>` anywhere to receive
 * all entries — the strategy/registry pattern:
 *
 * ```kotlin
 * @Provides @IntoMap("json") fun json(): Parser = JsonParser()
 * @Provides @IntoMap("xml")  fun xml(): Parser = XmlParser()
 *
 * @Injectable class Decoder @Inject constructor(parsers: Map<String, Parser>)
 * ```
 *
 * Entry keys must be unique per value type (compile-time error otherwise). Like
 * [IntoSet], contributions must be unscoped — entries are created per map resolution.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class IntoMap(val key: String)
