package com.kite.di.annotations

/**
 * Meta-annotation declaring a scope annotation.
 *
 * [level] orders lifetimes: lower = longer-lived. A binding may only depend on
 * bindings with equal or lower level (longer or equal lifetime) — checked at
 * compile time. Unscoped bindings (no scope annotation) create a new instance per
 * injection and may depend on anything.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Scope(val level: Int)

/** Application lifetime — one instance for the life of the process. */
@Scope(level = 0)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Singleton

/** Per-Activity lifetime; instances survive configuration changes. */
@Scope(level = 1)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ActivityScoped

/** Per-Fragment lifetime. */
@Scope(level = 2)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class FragmentScoped
