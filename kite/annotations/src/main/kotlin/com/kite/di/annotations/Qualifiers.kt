package com.kite.di.annotations

/**
 * Meta-annotation declaring a qualifier annotation. A binding key is
 * `(type, qualifier?)` — qualifiers distinguish multiple bindings of one type.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Qualifier

/** String-valued qualifier, the common case: `@Named("auth") client: OkHttpClient`. */
@Qualifier
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
)
@Retention(AnnotationRetention.BINARY)
annotation class Named(val value: String)
