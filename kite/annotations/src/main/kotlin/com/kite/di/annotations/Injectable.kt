package com.kite.di.annotations

import kotlin.reflect.KClass

/**
 * Marks a class as providable by the injector.
 *
 * The class is bound to its own type by default. Binding to an interface or a
 * superclass is explicit via [bindTo] — implicit "bind to all supertypes" is not
 * supported because it creates surprise duplicate bindings.
 *
 * Dependencies are the parameters of the class's single constructor (or the
 * constructor marked with [Inject] when there are several).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Injectable(
    /** Additional keys this class is bound to (interfaces/superclasses). */
    val bindTo: Array<KClass<*>> = [],
)

/**
 * On a constructor: selects it as the injection constructor when a class has several.
 * On a property (`lateinit var`): member injection, filled by `Kite.inject(target)` —
 * intended only for classes instantiated by the Android framework (Activities,
 * Fragments, Services, BroadcastReceivers).
 */
@Target(
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
)
@Retention(AnnotationRetention.BINARY)
annotation class Inject
