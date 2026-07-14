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
