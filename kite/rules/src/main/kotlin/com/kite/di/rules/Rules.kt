package com.kite.di.rules

import kotlin.reflect.KClass

/**
 * The decisions vocabulary — the few facts the code cannot express,
 * declared on one holder object per module (conventionally `GraphRules.kt`):
 *
 * ```kotlin
 * @Root(AppInitializer::class)
 * @Bind(UserRepository::class, to = NetworkUserRepository::class)
 * @Scoped(SessionCart::class, "Session", level = 10)
 * private object GraphRules
 * ```
 *
 * SOURCE retention: the annotations exist only for the KSP pass over this
 * module's sources and never reach bytecode. The Gradle plugin adds this module
 * as `compileOnly`, so these classes are never packaged into an APK either —
 * decisions are a dev-side compile input, exactly like graph.json is a
 * dev-side compile output.
 */

/**
 * R3: include [type] in the graph even though no declaration consumes it.
 * For classes resolved only at runtime — `by injected()` delegates and
 * `Kite.get<T>()` calls live in function bodies, which inference cannot see.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class Root(val type: KClass<*>)

/**
 * Choose [to] as the implementation injected for single-`type` sites when the
 * module has several implementations (`Set<type>` sites always receive all of
 * them). This is the answer to error E1 — and to a board decision card.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class Bind(val type: KClass<*>, val to: KClass<*>)

/**
 * Override [type]'s lifetime beyond the defaults (interface implementations are
 * singletons, everything else unscoped). [scope] is `"singleton"`, `"activity"`,
 * `"fragment"`, `"none"`, or a custom scope name — custom names declare the
 * scope and require an explicit [level] (opened at runtime via
 * `Kite.openScope(name, level)`).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class Scoped(val type: KClass<*>, val scope: String, val level: Int = UNSET_LEVEL)

/** Sentinel for [Scoped.level]: built-in scope names carry their own level. */
const val UNSET_LEVEL: Int = Int.MIN_VALUE
