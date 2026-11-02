package com.kite.di.runtime

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
