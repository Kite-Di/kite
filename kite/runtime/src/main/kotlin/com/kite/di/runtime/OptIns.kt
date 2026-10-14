package com.kite.di.runtime

/**
 * A lookup by type, checked when it runs instead of when it compiles.
 *
 * The processor reads declarations, not function bodies, so it cannot see a
 * `Kite.get<T>()` and cannot tell you that `T` has no binding, or that it lives in a
 * scope the caller does not have. Use the accessor the processor generates for the
 * type instead — `by analytics()` for `Analytics` — which does not exist when the
 * binding does not, and only exists on receivers that have the binding's scope.
 * Opting in accepts that this call site can throw at runtime.
 */
@RequiresOptIn(
    message = "Unchecked lookup: a missing binding or scope throws at runtime. " +
        "Use the generated accessor (e.g. `by analytics()` for Analytics), or opt in.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class UncheckedLookup

/**
 * Plumbing for code the processor generates: `Graph.start`, accessors, adapters. Not
 * for application code — every check the processor makes assumes it is the only
 * caller.
 */
@RequiresOptIn(
    message = "Kite plumbing for generated code. Call Graph.start and the generated accessors instead.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class KiteGeneratedApi
