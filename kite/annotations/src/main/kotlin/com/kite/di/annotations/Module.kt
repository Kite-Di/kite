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
